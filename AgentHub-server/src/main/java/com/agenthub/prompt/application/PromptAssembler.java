package com.agenthub.prompt.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.prompt.domain.enums.PromptPurpose;
import com.agenthub.prompt.domain.model.PromptAssemblyResult;
import com.agenthub.prompt.domain.model.PromptConstraintSnapshot;
import com.agenthub.prompt.domain.model.PromptContext;
import com.agenthub.prompt.domain.model.PromptLayout;
import com.agenthub.prompt.domain.model.PromptSegmentSnapshot;
import com.agenthub.prompt.domain.model.PromptTemplateRef;
import com.agenthub.prompt.domain.model.PromptTemplateSnapshot;
import com.agenthub.prompt.domain.repository.PromptSnapshotProvider;
import com.agenthub.prompt.domain.service.PromptOverrideReader;
import com.agenthub.prompt.domain.service.PromptPlatformLocks;
import com.agenthub.prompt.domain.service.PromptVariables;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 提示词统一组装门面。所属域：prompt。
 * <p>
 * 职责：把 L1 平台约束 + L2 用途模板（锁定段、开放段）+ L3（覆盖 / 追加区 / 历史业务段）
 * 拼成一份出站 system prompt，并做**一次**非递归变量替换。
 * CHAT 与非 CHAT 只在上游 Adapter 上不同，本类只有一条链路。
 * <p>
 * 固定顺序（不可调换，单测锁死）：
 * <pre>
 * [受保护 head]
 *   L1 当前生效 content
 *   + 平台侧追加句（ROUTER 路由说明，属平台约束延伸）
 *   + L2 全部 mutable=0 锁定段（相位内按 sort_no）
 * [可截断 tail]
 *   + L2 全部 mutable=1 开放段（相位内按 sort_no；L3 覆盖就地替换）
 *   + L3 追加区【补充要求】
 *   + 历史业务段【业务提示词】（过渡：紧贴 L3 收口，待并入 L3 后移除）
 * → 一次性变量替换（模板侧 {{var}} → 值；值侧永不二次解析）
 * → 安全阀裁剪（只裁 tail；head 是红线，宁可超预算也不砍）
 * </pre>
 * <p>
 * <b>预算口径（重要）</b>：本类的字符上限只是「单次组装的绝对安全阀」，用于挡住
 * 「模板里写了几十万字」这类数据事故，<b>不是</b>业务预算。业务预算只有一套
 * （{@code agenthub.prompt.system-prompt-max-chars}），由 runtime 的
 * {@code PromptBudgetTrimmer} 在拼完全部段之后统一按优先级让出。历史上这里曾按 24000 切一刀，
 * 与注入层的第二次截断口径打架，导致「该留的被砍、该砍的留下」，现已合并。
 * <p>
 * 为什么要把锁定段与开放段分相位：库里 sort_no 是人工填写的数字，开放段一旦填得比锁定段小，
 * 锁定契约就会被挤到业务语气之后——模型先读「怎么说」再读「必须怎么输出」，红线名存实亡。
 * 相位优先由代码保证，不依赖数据碰巧排对；详见 {@link PromptTemplateSnapshot#orderedSegmentsByPhase()}。
 * <p>
 * 阻断规则（故障 ≠ 业务空结果）：
 * <ul>
 *   <li>无生效 L1 → 抛 {@link ErrorCode#PROMPT_UNAVAILABLE}，不拼半截；</li>
 *   <li>purpose 无生效 L2（或钉死版不存在）→ 抛异常，禁止拿 CHAT 模板顶替其它用途；</li>
 *   <li>模板 purpose 与请求 purpose 不一致 → 抛异常；</li>
 *   <li>缺 required 段 / required 段正文为空 → 抛异常；</li>
 *   <li>override 的键在该版模板里不是 soft 段 → 记 notice 并忽略（模板升级后旧键失效不应炸线上）。</li>
 * </ul>
 * <p>
 * Runtime 注入（tools / skills / memory）**不在**本类：由既有 {@code PromptInjectionComposer}
 * 接在本类产出的 head + tail 之后，按「记忆 → Skill → 工具目录」续拼，避免两套拼接。
 * 本类通过 {@link PromptLayout} 把「受保护前缀长度」交给下游，下游预算裁剪不得越过它。
 * <p>
 * 线程安全：无状态单例；所有中间量都是方法局部变量。
 */
@Service
public class PromptAssembler {

    private static final Logger log = LoggerFactory.getLogger(PromptAssembler.class);

    /** 业务段标记，与 {@code LlmSpanTexts} 的展示口径一致 */
    static final String BUSINESS_MARK = "【业务提示词】";
    /** L3 追加区标记 */
    static final String EXTRA_MARK = "【补充要求】";
    /** 预算裁剪标记：出现即表示 tail 被截断，head 仍然完整 */
    static final String TRUNCATED_MARK = "\n\n（提示词已按预算截断）";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 组装正文的默认安全阀：远大于系统提示业务预算，只防「模板里写了几十万字」这类数据事故 */
    private static final int SAFETY_MAX_CHARS = 120_000;

    private final PromptSnapshotProvider snapshots;
    private final ObjectMapper json;
    private final boolean constraintEnabled;
    private final String defaultLocale;
    private final int maxChars;
    /** 平台侧固定追加句（测试用）；生产为空，改由 Context#constraintExtra 携带 */
    private final String platformClause;

    /**
     * Spring 装配用。平台侧 ROUTER 追加句按运行模式由 Adapter 放进 Context，故此处不需要它。
     * 类上有两个构造器，因此必须显式标注由容器使用哪一个。
     * <p>
     * 这里<b>没有</b>字符预算参数：预算只有一套（{@code agenthub.prompt.system-prompt-max-chars}），
     * 由 runtime 的裁剪器统一执行。组装层只留 {@link #SAFETY_MAX_CHARS} 这个代码常量防数据事故。
     *
     * @param snapshots         快照读取（缓存装饰器或 DB 直读）
     * @param json              Jackson，复用容器配置
     * @param constraintEnabled L1 是否参与组装。默认 true；仅在排障时关，关闭会失去平台红线
     * @param defaultLocale     请求未指定语言时的回落值
     */
    @Autowired
    public PromptAssembler(PromptSnapshotProvider snapshots,
                           ObjectMapper json,
                           @Value("${agenthub.prompt.assembler.constraint-enabled:true}") boolean constraintEnabled,
                           @Value("${agenthub.prompt.assembler.default-locale:zh-CN}") String defaultLocale) {
        this(snapshots, json, constraintEnabled, defaultLocale, SAFETY_MAX_CHARS, null);
    }

    /**
     * 只指定安全阀的构造器（测试常用）：不注入平台追加句。
     *
     * @param snapshots         快照读取
     * @param json              Jackson
     * @param constraintEnabled L1 是否参与组装
     * @param defaultLocale     默认语言
     * @param safetyMaxChars    单次组装的绝对安全阀（只防极端数据事故，不是业务预算）
     */
    public PromptAssembler(PromptSnapshotProvider snapshots, ObjectMapper json, boolean constraintEnabled,
                           String defaultLocale, int safetyMaxChars) {
        this(snapshots, json, constraintEnabled, defaultLocale, safetyMaxChars, null);
    }

    /**
     * 测试与手动装配用：额外给定一段固定平台追加句（不来自 Context）。
     * 有多个构造器时必须由本注解指明容器走哪个，否则 Spring 无法选择。
     *
     * @param snapshots         快照读取
     * @param json              Jackson
     * @param constraintEnabled L1 是否参与组装
     * @param defaultLocale     默认语言
     * @param safetyMaxChars    单次组装的绝对安全阀（只防极端数据事故，不是业务预算）
     * @param platformClause    平台侧固定追加句，可空；非空时优先于 Context#constraintExtra
     */
    public PromptAssembler(PromptSnapshotProvider snapshots, ObjectMapper json, boolean constraintEnabled,
                           String defaultLocale, int safetyMaxChars, String platformClause) {
        this.snapshots = snapshots;
        this.json = json;
        this.constraintEnabled = constraintEnabled;
        this.defaultLocale = defaultLocale == null || defaultLocale.isBlank() ? "zh-CN" : defaultLocale.trim();
        this.maxChars = safetyMaxChars <= 0 ? SAFETY_MAX_CHARS : safetyMaxChars;
        this.platformClause = platformClause == null ? "" : platformClause.trim();
    }

    /**
     * 组装出站系统提示。
     *
     * @param context 统一上下文，非空
     * @return 正文 + 版本 + 统计 + 结构描述（head 长度与各段渲染正文）
     * @throws BizException 阻断类失败（无 L1 / 无模板 / 缺必选段 / 变量未赋值）
     */
    public PromptAssemblyResult assemble(PromptContext context) {
        if (context == null) {
            throw new BizException(ErrorCode.PROMPT_UNAVAILABLE, "提示词上下文为空");
        }
        String locale = localeOf(context.getLocale());
        PromptPurpose purpose = PromptPurpose.tryParse(context.getPurpose())
                .orElseThrow(() -> new BizException(ErrorCode.PROMPT_UNAVAILABLE,
                        "未配置模板的用途：" + context.getPurpose()));

        PromptAssemblyResult result = new PromptAssemblyResult();
        result.setPurpose(purpose.name());
        result.setLocale(locale);

        // ---- 第一步：L1。无生效约束即阻断，绝不用文件或空串兜底 ----
        String constraint = loadConstraint(locale, result);

        // ---- 平台侧追加句（如 ROUTER 路由说明）：紧跟 L1，属于平台约束的延伸 ----
        // 它来自平台资源、Agent 不可编辑，因此位置在 L2 之前，语义上仍属「平台保底线」
        String clause = platformClause.isEmpty()
                ? (context.getConstraintExtra() == null ? "" : context.getConstraintExtra().trim())
                : platformClause;

        // ---- 第二步：L2 快照（钉死版优先，否则取该用途生效版）----
        PromptTemplateSnapshot template = loadTemplate(purpose, locale, context, result);
        result.setSegmentCount(template.orderedSegments().size());

        // 变量值先算好：段落在拼接时就要落地变量，避免「先拼后替换」时把用户输入当模板再扫一遍
        Map<String, String> values = variablesOf(context, purpose, locale);

        // ---- 第三步：段落两相位合并。锁定段进 head，开放段进 tail；override 只对开放段生效 ----
        Map<String, String> overrides = PromptOverrideReader.read(context.getOverrideJson(), json);
        List<PromptSegmentSnapshot> segments = template.orderedSegmentsByPhase();
        StringBuilder locked = new StringBuilder();
        StringBuilder soft = new StringBuilder();
        List<String> lockedBlocks = new ArrayList<>();
        List<String> tailBlocks = new ArrayList<>();
        int overridden = 0;
        for (PromptSegmentSnapshot segment : segments) {
            // 必选段缺失在这里阻断：拼一份缺契约的提示词比直接失败更危险
            if (segment.mandatory() && isBlank(segment.getContent())) {
                throw new BizException(ErrorCode.PROMPT_UNAVAILABLE,
                        "提示词模板缺必选段：" + segment.getSegKey() + "（" + template.getTemplateKey()
                                + " v" + template.getVersion() + "）");
            }
            String text = segment.getContent() == null ? "" : segment.getContent();
            if (segment.soft()) {
                String override = overrides.get(segment.getSegKey());
                if (override != null) {
                    text = override;
                    overridden++;
                }
            }
            if (text.isBlank()) {
                continue;
            }
            // 逐段渲染：布局里存的是渲染后正文，出站校验据此在最终提示词里定位锁定段
            String rendered = PromptVariables.render(text, values);
            if (segment.soft()) {
                appendBlock(soft, rendered);
                tailBlocks.add(rendered);
            } else {
                appendBlock(locked, rendered);
                lockedBlocks.add(rendered);
            }
        }
        result.setOverriddenSegmentCount(overridden);
        reportUncoveredOverrides(overrides, segments, result);

        // ---- 第四步：L3 追加区（append-only），随后历史业务段收口 ----
        // 业务段是过渡期遗留（agent_def.system_prompt）：先紧贴 L3 收口，等能并入 L3 后整段删除。
        // 位置刻意放在 L3 之后：它与「补充要求」语义重复，夹在 L2 与 L3 之间会割裂开放段连续性。
        String extra = context.getPromptExtra() == null ? "" : context.getPromptExtra().trim();
        String business = context.getBusinessSegment() == null ? "" : context.getBusinessSegment().trim();
        if (!extra.isEmpty()) {
            String rendered = PromptVariables.render(EXTRA_MARK + "\n" + extra, values);
            appendBlock(soft, rendered);
            tailBlocks.add(rendered);
        }
        if (!business.isEmpty()) {
            String rendered = PromptVariables.render(BUSINESS_MARK + "\n" + business, values);
            appendBlock(soft, rendered);
            tailBlocks.add(rendered);
        }
        result.setBusinessChars(business.length());
        result.setExtraChars(extra.length());

        // ---- 第五步：拼 head 与 tail。head = L1 + 平台追加句 + 锁定段，永不参与后续裁剪 ----
        String l1 = PromptVariables.render(constraint, values);
        if (!clause.isEmpty()) {
            String renderedClause = PromptVariables.render(clause, values);
            l1 = l1.isEmpty() ? renderedClause : l1 + "\n\n" + renderedClause;
        }
        String head = locked.length() == 0
                ? l1
                : (l1.isEmpty() ? locked.toString() : l1 + "\n\n" + locked);
        String tail = soft.toString();

        // ---- 第六步：安全阀。只可能截断 tail；head 是红线，宁可超预算也不砍 ----
        // 业务预算不在这里：全部段拼完之后由 runtime 的 PromptBudgetTrimmer 统一让出。
        // 这里的上限只防「模板写了几十万字」这类数据事故，故意配得远大于业务预算。
        int budget = context.getMaxChars() > 0 ? Math.min(context.getMaxChars(), maxChars) : maxChars;
        String assembled = head;
        boolean truncated = false;
        if (head.length() + (tail.isEmpty() ? 0 : tail.length() + 2) > budget) {
            truncated = true;
            // 预留标记本身的长度，避免「截断后又超一截」
            int room = budget - head.length() - TRUNCATED_MARK.length() - 2;
            if (room <= 0) {
                // 锁定段单独就吃掉预算：tail 全部省略并留痕，禁止为了压字数去砍 head
                result.getNotices().add("平台约束与锁定段已超出安全阀，可截断内容已全部省略");
                assembled = head + TRUNCATED_MARK;
            } else {
                String cutTail = tail.length() > room ? tail.substring(0, room) : tail;
                assembled = cutTail.isEmpty() ? head + TRUNCATED_MARK
                        : head + "\n\n" + cutTail + TRUNCATED_MARK;
            }
        } else if (!tail.isEmpty()) {
            assembled = head + "\n\n" + tail;
        }
        result.setTruncated(truncated);
        result.setText(assembled);
        // 结构描述：head 长度用于下游裁剪的下界，各段正文用于出站前的顺序校验
        result.setLayout(new PromptLayout(head.length(), lockedBlocks, tailBlocks));

        // 只记版本与长度：正文可能含业务描述与用户输入，不进日志
        log.info("提示词已组装 purpose={} locale={} l1v={} tpl={} v={} segs={} overridden={} headChars={} chars={} truncated={}",
                result.getPurpose(), result.getLocale(), result.getConstraintVersion(),
                result.getTemplateKey(), result.getTemplateVersion(), result.getSegmentCount(),
                result.getOverriddenSegmentCount(), head.length(), assembled.length(), truncated);
        return result;
    }

    /**
     * 只拼某用途生效模板的**锁定段**（mutable=0），再追加一段可选的业务要求。
     * <p>
     * 专用场景：记忆抽取（MEMORY）。记忆的抽取契约与输出格式是平台红线，
     * Agent 只能追加场景要求，不能钉死旧版、也不能用 L3 覆盖段替换格式；
     * 因此这里**不拼 L1、不读 override**，避免旧 Agent 的 key/value/confidence 覆盖值回流。
     * <p>
     * 与 {@link #assemble(PromptContext)} 的共同点：都按平台已发布模板的相位顺序取段、
     * 都做一次性变量替换；区别只在于本方法只取锁定段且不注入 L1。
     * <p>
     * 阻断规则：用途无生效模板、缺 required 锁定段、缺该用途必须存在的锁定段（如 MEMORY 的
     * {@code output_format}）都直接抛 {@link ErrorCode#PROMPT_UNAVAILABLE}，绝不拿半截提示词出站。
     *
     * @param purpose     用途码（大小写不敏感）
     * @param locale      语言；空白按默认
     * @param promptExtra 追加要求（append-only），可空
     * @return 锁定段正文 + 可选的【补充要求】
     * @throws BizException 模板未发布、用途不匹配或缺锁定段
     */
    public String assembleLockedByPurpose(String purpose, String locale, String promptExtra) {
        PromptPurpose parsed = PromptPurpose.tryParse(purpose)
                .orElseThrow(() -> new BizException(ErrorCode.PROMPT_UNAVAILABLE,
                        "未配置模板的用途：" + purpose));
        String resolvedLocale = localeOf(locale);
        PromptTemplateRef ref = snapshots.activeRefByPurpose(parsed.name(), resolvedLocale)
                .orElseThrow(() -> new BizException(ErrorCode.PROMPT_UNAVAILABLE,
                        "用途未配置模板：" + parsed.name() + "（" + resolvedLocale + "）"));
        PromptTemplateSnapshot template = requireSnapshot(ref);
        if (!parsed.name().equalsIgnoreCase(template.getPurpose())) {
            throw new BizException(ErrorCode.PROMPT_UNAVAILABLE,
                    "提示词模板用途不匹配：请求 " + parsed.name() + "，模板 " + template.getPurpose());
        }

        Map<String, String> values = lockedOnlyVariables(parsed, resolvedLocale);
        // 运行时白名单：非空时只拼列出的锁定段（MEMORY 排除只锁定编辑的 memory_style 旧格式）
        Set<String> runtimeKeys = PromptPlatformLocks.runtimeKeys(parsed.name());
        StringBuilder body = new StringBuilder();
        Set<String> lockedKeys = new LinkedHashSet<>();
        for (PromptSegmentSnapshot segment : template.orderedSegmentsByPhase()) {
            // 只认锁定段：开放段属于可被 L3 覆盖的业务语气，不能进入记忆抽取的格式红线
            if (segment.soft()) {
                continue;
            }
            // 遗留锁定段（如 memory_style）只锁编辑、不出站：旧 key/value/confidence 不得回流
            if (!runtimeKeys.isEmpty() && !runtimeKeys.contains(segment.getSegKey())) {
                continue;
            }
            if (segment.mandatory() && isBlank(segment.getContent())) {
                throw new BizException(ErrorCode.PROMPT_UNAVAILABLE,
                        "提示词模板缺必选段：" + segment.getSegKey() + "（" + template.getTemplateKey()
                                + " v" + template.getVersion() + "）");
            }
            if (isBlank(segment.getContent())) {
                continue;
            }
            appendBlock(body, PromptVariables.render(segment.getContent(), values));
            lockedKeys.add(segment.getSegKey());
        }
        // 平台要求的锁定段必须真实存在：旧版 MEMORY 模板只有抽取契约、没有输出格式，
        // 若继续出站，模型会自行编造字段（历史上就是 key/value/confidence），必须阻断换新版模板。
        for (String requiredKey : PromptPlatformLocks.requiredKeys(parsed.name())) {
            if (!lockedKeys.contains(requiredKey)) {
                throw new BizException(ErrorCode.PROMPT_UNAVAILABLE,
                        "提示词模板缺平台锁定段：" + requiredKey + "（" + template.getTemplateKey()
                                + " v" + template.getVersion() + "），请先执行对应增量 SQL");
            }
        }
        if (body.length() == 0) {
            throw new BizException(ErrorCode.PROMPT_UNAVAILABLE,
                    "提示词模板没有可用的锁定段：" + template.getTemplateKey() + " v" + template.getVersion());
        }
        String extra = promptExtra == null ? "" : promptExtra.trim();
        if (!extra.isEmpty()) {
            appendBlock(body, EXTRA_MARK + "\n" + extra);
        }
        // 只记版本与长度：正文含平台契约，不整段进日志
        log.info("锁定段提示词已组装 purpose={} locale={} tpl={} v={} locked={} extraChars={} chars={}",
                parsed.name(), resolvedLocale, template.getTemplateKey(), template.getVersion(),
                lockedKeys.size(), extra.length(), body.length());
        return body.toString();
    }

    /**
     * 锁定段组装用的变量表：只给已注册变量，避免模板里写了 {{current_time}} 时直接炸。
     * 用户输入不在本方法参与（待抽正文由调用方作为用户消息单独传入）。
     */
    private Map<String, String> lockedOnlyVariables(PromptPurpose purpose, String locale) {
        Map<String, String> values = new HashMap<>(PromptVariables.registry().size());
        values.put(PromptVariables.PURPOSE, purpose.name());
        values.put(PromptVariables.LOCALE, locale);
        values.put(PromptVariables.AGENT_NAME, "");
        values.put(PromptVariables.USER_QUERY, "");
        values.put(PromptVariables.HISTORY, "");
        values.put(PromptVariables.ROUTE_TABLE, "");
        values.put(PromptVariables.CURRENT_TIME, LocalDateTime.now(ZONE).format(TIME_FORMAT));
        return values;
    }

    /**
     * 只取 L1（配置页只读展示、出站前缀校验用）。无生效版返回空串，由调用方决定是否失败。
     *
     * @param locale 语言；空白按默认
     * @return 当前生效 L1 正文，未发布时为 ""
     */
    public String constraintText(String locale) {
        if (!constraintEnabled) {
            return "";
        }
        return snapshots.activeConstraint(localeOf(locale))
                .map(PromptConstraintSnapshot::getContent)
                .orElse("");
    }

    /**
     * L1 是否参与组装。关闭时本类不阻断，仅供排障；正常部署必须打开。
     *
     * @return true 表示启用
     */
    public boolean isConstraintEnabled() {
        return constraintEnabled;
    }

    /**
     * 取 L1 并写入结果。关闭 L1 时直接返回空串（仅在 constraint-enabled=false 时发生）。
     */
    private String loadConstraint(String locale, PromptAssemblyResult result) {
        if (!constraintEnabled) {
            result.setConstraintVersion(0);
            return "";
        }
        Optional<PromptConstraintSnapshot> hit = snapshots.activeConstraint(locale);
        if (hit.isEmpty()) {
            // 阻断出站：不允许「没模板还继续聊」，也不允许拿文件兜底
            throw new BizException(ErrorCode.PROMPT_UNAVAILABLE,
                    "平台约束（L1）未发布，语言 " + locale + "，已阻断出站");
        }
        PromptConstraintSnapshot constraint = hit.get();
        if (isBlank(constraint.getContent())) {
            throw new BizException(ErrorCode.PROMPT_UNAVAILABLE,
                    "平台约束（L1）正文为空，已阻断出站");
        }
        result.setConstraintVersion(constraint.getVersion());
        return constraint.getContent();
    }

    /**
     * 取 L2 快照。
     * <p>
     * 优先级：钉死 templateId → 钉死 templateKey+version → 该用途生效版。
     * 三种都取不到即阻断；取到的模板 purpose 与请求不一致也阻断（禁止 CHAT 顶替其它用途）。
     */
    private PromptTemplateSnapshot loadTemplate(PromptPurpose purpose, String locale, PromptContext context,
                                                PromptAssemblyResult result) {
        PromptTemplateSnapshot template = null;
        if (context.getTemplateId() != null) {
            template = snapshots.templateById(context.getTemplateId())
                    .orElseThrow(() -> new BizException(ErrorCode.PROMPT_UNAVAILABLE,
                            "引用的提示词模板不存在或未发布：id=" + context.getTemplateId()));
            assertVersionPinned(template, context);
        } else if (!isBlank(context.getTemplateKey()) && context.getTemplateVersion() != null) {
            template = snapshots.templateByKey(context.getTemplateKey().trim(), locale, context.getTemplateVersion())
                    .orElseThrow(() -> new BizException(ErrorCode.PROMPT_UNAVAILABLE,
                            "引用的提示词模板不存在或未发布：" + context.getTemplateKey()
                                    + " v" + context.getTemplateVersion()));
        } else if (!isBlank(context.getTemplateKey())) {
            // 只给了键没给版本：取该键当前生效版（管理端预览 / 未钉死的配置）
            PromptTemplateRef ref = snapshots.activeRefByKey(context.getTemplateKey().trim(), locale)
                    .orElseThrow(() -> new BizException(ErrorCode.PROMPT_UNAVAILABLE,
                            "提示词模板未发布生效：" + context.getTemplateKey()));
            template = requireSnapshot(ref);
        }
        if (template == null) {
            // 完全没钉死：按用途取生效版；没有就是「用途未配置模板」
            PromptTemplateRef ref = snapshots.activeRefByPurpose(purpose.name(), locale)
                    .orElseThrow(() -> new BizException(ErrorCode.PROMPT_UNAVAILABLE,
                            "用途未配置模板：" + purpose.name() + "（" + locale + "）"));
            template = requireSnapshot(ref);
        }
        if (!purpose.name().equalsIgnoreCase(template.getPurpose())) {
            throw new BizException(ErrorCode.PROMPT_UNAVAILABLE,
                    "提示词模板用途不匹配：请求 " + purpose.name() + "，模板 " + template.getPurpose());
        }
        result.setTemplateId(template.getId());
        result.setTemplateKey(template.getTemplateKey());
        result.setTemplateVersion(template.getVersion());
        return template;
    }

    /**
     * 引用存在但快照缺失（如引用指向草稿）时按阻断处理。
     */
    private PromptTemplateSnapshot requireSnapshot(PromptTemplateRef ref) {
        return snapshots.templateByKey(ref.getTemplateKey(), ref.getLocale(), ref.getVersion())
                .orElseThrow(() -> new BizException(ErrorCode.PROMPT_UNAVAILABLE,
                        "提示词模板快照缺失：" + ref.getTemplateKey() + " v" + ref.getVersion()));
    }

    /**
     * 钉死版本校验：主键与库中行不一致说明数据被改过，直接阻断而不是悄悄换版。
     */
    private void assertVersionPinned(PromptTemplateSnapshot template, PromptContext context) {
        if (context.getTemplateVersion() != null
                && !context.getTemplateVersion().equals(template.getVersion())) {
            throw new BizException(ErrorCode.PROMPT_UNAVAILABLE,
                    "提示词模板版本漂移：钉死 v" + context.getTemplateVersion()
                            + "，实际 v" + template.getVersion());
        }
        if (!isBlank(context.getTemplateKey())
                && !context.getTemplateKey().trim().equals(template.getTemplateKey())) {
            throw new BizException(ErrorCode.PROMPT_UNAVAILABLE,
                    "提示词模板键不匹配：配置 " + context.getTemplateKey() + "，实际 " + template.getTemplateKey());
        }
    }

    /**
     * override 里出现该版模板没有的键（或命中了锁定段）：留痕但不失败。
     * 这通常发生在模板升级后，旧 override 应提示清理；线上对话不该因此中断。
     */
    private void reportUncoveredOverrides(Map<String, String> overrides, List<PromptSegmentSnapshot> segments,
                                          PromptAssemblyResult result) {
        if (overrides.isEmpty()) {
            return;
        }
        Map<String, PromptSegmentSnapshot> byKey = new LinkedHashMap<>();
        for (PromptSegmentSnapshot segment : segments) {
            byKey.put(segment.getSegKey(), segment);
        }
        for (String key : overrides.keySet()) {
            PromptSegmentSnapshot segment = byKey.get(key);
            if (segment == null) {
                result.getNotices().add("L3 覆盖段已不存在，已忽略：" + key);
            } else if (!segment.soft()) {
                result.getNotices().add("L3 覆盖段为锁定段，已忽略：" + key);
            }
        }
    }

    /**
     * 变量值表。用户输入只在这里作为 value 出现，绝不再被当成模板解析。
     */
    private Map<String, String> variablesOf(PromptContext context, PromptPurpose purpose, String locale) {
        Map<String, String> values = new HashMap<>(PromptVariables.registry().size() + 4);
        values.put(PromptVariables.PURPOSE, purpose.name());
        values.put(PromptVariables.LOCALE, locale);
        values.put(PromptVariables.AGENT_NAME, context.getAgentName() == null ? "" : context.getAgentName());
        values.put(PromptVariables.USER_QUERY, context.getUserQuery() == null ? "" : context.getUserQuery());
        // 会话历史与路由表由 Runtime 后置注入；本组装给空串，避免模板里的这两个变量直接炸
        values.put(PromptVariables.HISTORY, "");
        values.put(PromptVariables.ROUTE_TABLE, "");
        values.put(PromptVariables.CURRENT_TIME, LocalDateTime.now(ZONE).format(TIME_FORMAT));
        if (context.getVariables() != null) {
            for (Map.Entry<String, String> entry : context.getVariables().entrySet()) {
                // 只允许覆盖已注册变量；未注册的键忽略，防止调用方偷偷扩白名单
                if (PromptVariables.registered(entry.getKey()) && entry.getValue() != null) {
                    values.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return values;
    }

    /**
     * 追加一段，块之间空一行。空块不追加，避免出现连续空行。
     */
    private static void appendBlock(StringBuilder body, String text) {
        if (body.length() > 0) {
            body.append("\n\n");
        }
        body.append(text);
    }

    private String localeOf(String raw) {
        return raw == null || raw.isBlank() ? defaultLocale : raw.trim();
    }

    private static boolean isBlank(String raw) {
        return raw == null || raw.isBlank();
    }
}
