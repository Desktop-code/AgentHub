package com.agenthub.prompt.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.prompt.domain.enums.PromptPurpose;
import com.agenthub.prompt.domain.model.PromptAssemblyResult;
import com.agenthub.prompt.domain.model.PromptConstraintSnapshot;
import com.agenthub.prompt.domain.model.PromptContext;
import com.agenthub.prompt.domain.model.PromptSegmentSnapshot;
import com.agenthub.prompt.domain.model.PromptTemplateRef;
import com.agenthub.prompt.domain.model.PromptTemplateSnapshot;
import com.agenthub.prompt.domain.repository.PromptSnapshotProvider;
import com.agenthub.prompt.domain.service.PromptOverrideReader;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 提示词统一组装门面。所属域：prompt。
 * <p>
 * 职责：把 L1 平台约束 + L2 用途模板（core 锁定、soft 可覆盖）+ L3（覆盖 / 追加 / 历史业务段）
 * 拼成一份出站 system prompt，并做**一次**非递归变量替换。
 * CHAT 与非 CHAT 只在上游 Adapter 上不同，本类只有一条链路。
 * <p>
 * 固定顺序（不可调换，单测锁死）：
 * <pre>
 * [L1 当前生效 content]
 * + [L2 各段按 sort_no：mutable=0 用库文；mutable=1 用 override 否则库文]
 * + [历史用户业务段 agent_def.system_prompt，带「业务提示词」标记]
 * + [L3 prompt_extra 追加区]
 * → 一次性变量替换（模板侧 {{var}} → 值；值侧永不二次解析）
 * → 预算裁剪（L1 与 core 永不裁）
 * </pre>
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
 * Runtime 注入（tools / skills / RAG / memory）**不在**本类：仍由既有
 * {@code PromptInjectionComposer} 在 Assembler 之后追加，避免两套拼接。
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

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

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
     *
     * @param snapshots         快照读取（缓存装饰器或 DB 直读）
     * @param json              Jackson，复用容器配置
     * @param constraintEnabled L1 是否参与组装。默认 true；仅在排障时关，关闭会失去平台红线
     * @param defaultLocale     请求未指定语言时的回落值
     * @param maxChars          组装正文上限
     */
    @Autowired
    public PromptAssembler(PromptSnapshotProvider snapshots,
                           ObjectMapper json,
                           @Value("${agenthub.prompt.assembler.constraint-enabled:true}") boolean constraintEnabled,
                           @Value("${agenthub.prompt.assembler.default-locale:zh-CN}") String defaultLocale,
                           @Value("${agenthub.prompt.assembler.max-chars:24000}") int maxChars) {
        this(snapshots, json, constraintEnabled, defaultLocale, maxChars, null);
    }

    /**
     * 测试与手动装配用：额外给定一段固定平台追加句（不来自 Context）。
     * 有多个构造器时必须由本注解指明容器走哪个，否则 Spring 无法选择。
     *
     * @param snapshots         快照读取
     * @param json              Jackson
     * @param constraintEnabled L1 是否参与组装
     * @param defaultLocale     默认语言
     * @param maxChars          正文上限
     * @param platformClause    平台侧固定追加句，可空；非空时优先于 Context#constraintExtra
     */
    public PromptAssembler(PromptSnapshotProvider snapshots, ObjectMapper json, boolean constraintEnabled,
                           String defaultLocale, int maxChars, String platformClause) {
        this.snapshots = snapshots;
        this.json = json;
        this.constraintEnabled = constraintEnabled;
        this.defaultLocale = defaultLocale == null || defaultLocale.isBlank() ? "zh-CN" : defaultLocale.trim();
        this.maxChars = maxChars <= 0 ? 24_000 : maxChars;
        this.platformClause = platformClause == null ? "" : platformClause.trim();
    }

    /**
     * 组装出站系统提示。
     *
     * @param context 统一上下文，非空
     * @return 正文 + 版本 + 统计
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

        // ---- 第三步：段落合并。override 只对 soft 段生效 ----
        Map<String, String> overrides = PromptOverrideReader.read(context.getOverrideJson(), json);
        List<PromptSegmentSnapshot> segments = template.orderedSegments();
        int overridden = 0;
        StringBuilder body = new StringBuilder();
        for (PromptSegmentSnapshot segment : segments) {
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
            if (!text.isBlank()) {
                appendBlock(body, text);
            }
        }
        result.setOverriddenSegmentCount(overridden);
        reportUncoveredOverrides(overrides, segments, result);

        // ---- 第四步：历史业务段（agent_def.system_prompt），L2 之后、extra 之前 ----
        String business = context.getBusinessSegment() == null ? "" : context.getBusinessSegment().trim();
        if (!business.isEmpty()) {
            appendBlock(body, BUSINESS_MARK + "\n" + business);
        }
        result.setBusinessChars(business.length());

        // ---- 第五步：L3 追加区（append-only）----
        String extra = context.getPromptExtra() == null ? "" : context.getPromptExtra().trim();
        if (!extra.isEmpty()) {
            appendBlock(body, EXTRA_MARK + "\n" + extra);
        }
        result.setExtraChars(extra.length());

        // ---- 第六步：一次性变量替换。骨架来自 L1..L3，值只做字面量落地 ----
        Map<String, String> values = variablesOf(context, purpose, locale);
        String l1 = PromptVariables.render(constraint, values);
        if (!clause.isEmpty()) {
            l1 = l1.isEmpty() ? PromptVariables.render(clause, values)
                    : l1 + "\n\n" + PromptVariables.render(clause, values);
        }
        String rest = PromptVariables.render(body.toString(), values);

        // ---- 第七步：预算裁剪，L1 与 core 已在不裁的段落里；此处只可能截断尾部 ----
        String assembled = l1.isEmpty() ? rest : (rest.isEmpty() ? l1 : l1 + "\n\n" + rest);
        int budget = context.getMaxChars() > 0 ? Math.min(context.getMaxChars(), maxChars) : maxChars;
        if (assembled.length() > budget) {
            assembled = assembled.substring(0, budget) + "\n\n（提示词已按预算截断）";
            result.setTruncated(true);
        }
        result.setText(assembled);

        // 只记版本与长度：正文可能含业务描述与用户输入，不进日志
        log.info("提示词已组装 purpose={} locale={} l1v={} tpl={} v={} segs={} overridden={} chars={} truncated={}",
                result.getPurpose(), result.getLocale(), result.getConstraintVersion(),
                result.getTemplateKey(), result.getTemplateVersion(), result.getSegmentCount(),
                result.getOverriddenSegmentCount(), assembled.length(), result.isTruncated());
        return result;
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
