package com.agenthub.prompt.application;

import com.agenthub.agent.domain.model.AgentPromptBinding;
import com.agenthub.agent.domain.model.AgentPromptPreviewResult;
import com.agenthub.agent.domain.model.AgentPromptTemplateOption;
import com.agenthub.agent.domain.model.AgentPromptTemplateSegment;
import com.agenthub.agent.domain.model.AgentPromptTemplateView;
import com.agenthub.agent.domain.service.AgentPromptTemplatePort;
import com.agenthub.agent.domain.service.PlatformPromptPrefix;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.prompt.domain.enums.PromptPublishStatus;
import com.agenthub.prompt.domain.enums.PromptPurpose;
import com.agenthub.prompt.domain.model.PromptAssemblyResult;
import com.agenthub.prompt.domain.model.PromptContext;
import com.agenthub.prompt.domain.model.PromptSegment;
import com.agenthub.prompt.domain.model.PromptSegmentSnapshot;
import com.agenthub.prompt.domain.model.PromptTemplate;
import com.agenthub.prompt.domain.repository.PromptSegmentRepository;
import com.agenthub.prompt.domain.repository.PromptTemplateRepository;
import com.agenthub.prompt.domain.service.PromptOverrideReader;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L3 提示词挂接的 prompt 侧实现。所属域：prompt。
 * <p>
 * 职责（只做「读模板 / 校验 / 组装预览」三件事，不写 agent 表）：
 * <ol>
 *   <li>{@link #publishedOptions} / {@link #view}：给配置页拉可选的已发布版本与钉死版段落；</li>
 *   <li>{@link #normalize}：保存硬闸——覆盖键必须落在该钉死版的 soft 段里，版本必须钉死且可解析；</li>
 *   <li>{@link #preview}：走与出站**同一条** {@link PromptAssembler#assemble} 路径，绝不调模型。</li>
 * </ol>
 * <p>
 * 为什么预览放在这里而不是 runtime：L1/L2 的读取口径、缺模板的失败语义都只有 Assembler 一份；
 * 在别处再拼一次就会出现「预览能看、出站却失败」的双轨，正是架构定稿 §5.5 禁止的。
 * <p>
 * 线程安全：无状态；Assembler 与仓储均可并发调用。
 */
@Service
public class AgentPromptBindingService implements AgentPromptTemplatePort {

    private static final Logger log = LoggerFactory.getLogger(AgentPromptBindingService.class);

    /** L3 追加区上限（Java char）。与 system_prompt 的用户段上限保持一致，避免出现更宽松的旁路 */
    static final int EXTRA_MAX = 12_000;
    /** 单个 soft 段覆盖文案上限，与段落正文上限同源 */
    static final int OVERRIDE_VALUE_MAX = 12_000;

    private final PromptTemplateRepository templates;
    private final PromptSegmentRepository segments;
    private final PromptAssembler assembler;
    private final ObjectMapper json;
    /**
     * ROUTER 场景的平台追加句来源。用 ObjectProvider 容忍 prompt 域单测里不装配 agent 域组件；
     * 缺失时预览只少这一句平台说明，不影响其它链路。依赖方向仍是 prompt → agent，不会成环。
     */
    private final ObjectProvider<PlatformPromptPrefix> prefixProvider;

    /**
     * @param templates       L2 主表仓储
     * @param segments        L2 段落仓储
     * @param assembler       统一组装门面（预览唯一入口）
     * @param json            Jackson，复用容器配置
     * @param prefixProvider  平台追加句；可缺省
     */
    public AgentPromptBindingService(PromptTemplateRepository templates, PromptSegmentRepository segments,
                                     PromptAssembler assembler, ObjectMapper json,
                                     ObjectProvider<PlatformPromptPrefix> prefixProvider) {
        this.templates = templates;
        this.segments = segments;
        this.assembler = assembler;
        this.json = json;
        this.prefixProvider = prefixProvider;
    }

    /**
     * {@inheritDoc}
     * 口径：只有 {@link PromptPurpose} 覆盖的用途才有 L2 模板维度；
     * VISION / ASR / TTS / GUARDRAIL 等非生成槽不挂模板，前端据此隐藏「提示词」入口。
     */
    @Override
    public boolean supportsPurpose(String purpose) {
        return PromptPurpose.tryParse(purpose).isPresent();
    }

    /** {@inheritDoc} */
    @Override
    public List<AgentPromptTemplateOption> publishedOptions(String purpose, String locale) {
        String purposeCode = purposeOf(purpose);
        if (purposeCode == null) {
            return List.of();
        }
        List<AgentPromptTemplateOption> options = new ArrayList<>();
        for (PromptTemplate row : templates.listPublishedByPurpose(purposeCode, PromptChecks.locale(locale))) {
            AgentPromptTemplateOption option = new AgentPromptTemplateOption();
            option.setId(row.getId());
            option.setTemplateKey(row.getTemplateKey());
            option.setName(row.getName());
            option.setPurpose(row.getPurpose());
            option.setLocale(row.getLocale());
            option.setVersion(row.getVersion());
            option.setIsActive(row.getIsActive());
            option.setUpdateTime(row.getUpdateTime());
            options.add(option);
        }
        return options;
    }

    /** {@inheritDoc} */
    @Override
    public AgentPromptTemplateView view(Long templateId) {
        PromptTemplate row = requirePublishedTemplate(templateId);
        AgentPromptTemplateView view = new AgentPromptTemplateView();
        view.setId(row.getId());
        view.setTemplateKey(row.getTemplateKey());
        view.setName(row.getName());
        view.setPurpose(row.getPurpose());
        view.setLocale(row.getLocale());
        view.setVersion(row.getVersion());
        view.setIsActive(row.getIsActive());
        List<AgentPromptTemplateSegment> items = new ArrayList<>();
        for (PromptSegment segment : segments.listByTemplateId(row.getId())) {
            AgentPromptTemplateSegment item = new AgentPromptTemplateSegment();
            item.setSegKey(segment.getSegKey());
            item.setDisplayName(segment.getDisplayName());
            item.setSortNo(segment.getSortNo());
            item.setMutable(segment.getMutable());
            item.setRequired(segment.getRequired());
            item.setContent(segment.getContent());
            items.add(item);
        }
        view.setSegments(items);
        return view;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 校验顺序刻意从「引用」到「内容」：先确认模板存在且用途对得上，再校验覆盖键。
     * 这样报错文案能直接指出问题（用途不符 / 版本漂移 / 非法段），而不是笼统的「保存失败」。
     */
    @Override
    public AgentPromptBinding normalize(String purpose, Long templateId, Integer templateVersion,
                                        Map<String, String> override, String promptExtra) {
        String extra = normalizeExtra(promptExtra);
        // 未挂模板：不允许只留版本或只留覆盖，否则库里会出现无法解释的残值
        if (templateId == null) {
            if (templateVersion != null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "未选择提示词模板时不能指定模板版本");
            }
            if (hasAnyOverride(override)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "未选择提示词模板时不能提交段落覆盖，请先选定模板");
            }
            return new AgentPromptBinding(null, null, null, extra);
        }
        if (templateVersion == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "必须钉死提示词模板版本，不能只保存模板 id");
        }
        PromptTemplate row = requirePublishedTemplate(templateId);
        String expected = purposeOf(purpose);
        if (expected == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该用途不支持提示词模板：" + purpose);
        }
        // 用途对齐：CHAT 不能挂 ROUTER 模板，非 CHAT 槽也不能挂主对话模板
        if (!expected.equalsIgnoreCase(row.getPurpose())) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "模板用途不匹配：该配置用途为 " + expected + "，所选模板用途为 " + row.getPurpose());
        }
        if (!templateVersion.equals(row.getVersion())) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "模板版本不一致：提交 v" + templateVersion + "，该模板实际为 v" + row.getVersion());
        }
        String overrideJson = toOverrideJson(row.getId(), override);
        return new AgentPromptBinding(row.getId(), row.getVersion(), overrideJson, extra);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 用 {@link PromptContext} 直接喂 Assembler，而不是复用 runtime 的 SystemPromptAssembler：
     * 后者只接受 AgentDef 且按 runMode 猜用途，非 CHAT 槽（REWRITE / SUMMARY / MEMORY）预览不到。
     * ROUTER 的平台追加句仍在此补齐，保证预览正文与试跑出站一致。
     */
    @Override
    public AgentPromptPreviewResult preview(String purpose, String locale, Long templateId, Integer templateVersion,
                                            Map<String, String> override, String promptExtra,
                                            String agentName, String userQuery) {
        if (templateId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "预览前请先选择提示词模板");
        }
        // 复用保存闸：预览不允许演示「保存一定失败」的非法覆盖，避免使用者以为这样能上线
        AgentPromptBinding binding = normalize(purpose, templateId, templateVersion, override, promptExtra);

        PromptContext context = PromptContext.builder()
                .purpose(purposeOf(purpose))
                .locale(PromptChecks.locale(locale))
                .templateId(binding.getTemplateId())
                .templateVersion(binding.getTemplateVersion())
                .overrideJson(binding.getOverrideJson())
                .promptExtra(binding.getPromptExtra())
                .agentName(agentName)
                .userQuery(userQuery)
                .build();
        PromptPurpose parsed = PromptPurpose.parse(context.getPurpose());
        String clause = routerClause();
        if (!clause.isBlank()) {
            context.setConstraintExtra(PromptContextAdapters.clauseFor(parsed, clause));
        }

        PromptAssemblyResult assembled = assembler.assemble(context);
        AgentPromptPreviewResult result = new AgentPromptPreviewResult();
        result.setText(assembled.getText());
        result.setPurpose(assembled.getPurpose());
        result.setLocale(assembled.getLocale());
        result.setConstraintVersion(assembled.getConstraintVersion());
        result.setTemplateKey(assembled.getTemplateKey());
        result.setTemplateVersion(assembled.getTemplateVersion());
        result.setSegmentCount(assembled.getSegmentCount());
        result.setOverriddenSegmentCount(assembled.getOverriddenSegmentCount());
        result.setTruncated(assembled.isTruncated());
        result.setNotices(new ArrayList<>(assembled.getNotices()));
        // 只记版本与长度：预览正文含模板正文与用户输入，不进日志
        log.info("L3 预览完成 purpose={} tpl={} v={} chars={}",
                result.getPurpose(), result.getTemplateKey(), result.getTemplateVersion(),
                result.getText() == null ? 0 : result.getText().length());
        return result;
    }

    /**
     * {@inheritDoc}
     * 复用 {@code PromptOverrideReader} 的解析口径，但把异常收成空 Map：
     * 详情页读历史脏值时应当「显示为空 + 提示重存」，而不是整页 500。
     */
    @Override
    public Map<String, String> readOverrideSafe(String overrideJson) {
        try {
            return PromptOverrideReader.read(overrideJson, json);
        } catch (BizException ex) {
            log.warn("L3 覆盖 JSON 不可解析，按无覆盖回显：{}", ex.getMessage());
            return Map.of();
        }
    }

    /**
     * 取平台 ROUTER 追加句。prompt 域在单测里可能没有 agent 域组件，此时按无追加句处理。
     *
     * @return 追加句；不可用时为空串
     */
    private String routerClause() {
        if (prefixProvider == null) {
            return "";
        }
        PlatformPromptPrefix prefix = prefixProvider.getIfAvailable();
        return prefix == null ? "" : prefix.routerClause();
    }

    /**
     * 取已发布的模板版本行。草稿也当作「不存在」：钉死草稿等于绑了一个永不生效的版本。
     *
     * @param templateId 版本行主键
     * @return 已发布模板
     * @throws BizException 不存在或未发布
     */
    private PromptTemplate requirePublishedTemplate(Long templateId) {
        PromptTemplate row = templates.findById(templateId)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "提示词模板不存在：" + templateId));
        if (row.getStatus() == null || row.getStatus() != PromptPublishStatus.PUBLISHED.getCode()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "提示词模板尚未发布，不能挂到 Agent：" + row.getTemplateKey() + " v" + row.getVersion());
        }
        return row;
    }

    /**
     * 用途规范化：不认识或不适配的用途返回 null，由调用方决定报错还是返回空列表。
     *
     * @param raw 用途码
     * @return 规范大写用途；不可用时 null
     */
    private String purposeOf(String raw) {
        return PromptPurpose.tryParse(raw).map(Enum::name).orElse(null);
    }

    /**
     * 追加区：只去首尾空白，超长直接拒绝（不静默截断，避免用户以为整段生效）。
     *
     * @param raw 追加区原文
     * @return 入库值；空白为 null
     */
    private String normalizeExtra(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.length() > EXTRA_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST, "提示词追加区超过长度，最长 " + EXTRA_MAX);
        }
        return text;
    }

    private static boolean hasAnyOverride(Map<String, String> override) {
        if (override == null || override.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> entry : override.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank()
                    && entry.getValue() != null && !entry.getValue().isBlank()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 校验覆盖键并序列化。
     * <p>
     * 为什么这里「有值才写」：空串覆盖会整段抹掉 soft 段正文，而 Assembler 的
     * {@code PromptOverrideReader} 也是按「空白=未覆盖」处理；两处口径必须一致，
     * 否则会出现「保存了空串，预览却回退库文」的困惑。
     *
     * @param templateId 钉死版主键
     * @param override   seg_key → 文案
     * @return JSON；无有效覆盖时为 null
     * @throws BizException 键为该版 core 段或不存在
     */
    private String toOverrideJson(Long templateId, Map<String, String> override) {
        if (!hasAnyOverride(override)) {
            return null;
        }
        // 该版全部段的 soft 集合与全集：用于区分「core 段」和「完全不存在的段」，报错文案不同
        Set<String> softKeys = new LinkedHashSet<>();
        Set<String> allKeys = new LinkedHashSet<>();
        for (PromptSegmentSnapshot segment : segmentsOf(templateId)) {
            allKeys.add(segment.getSegKey());
            if (segment.soft()) {
                softKeys.add(segment.getSegKey());
            }
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : override.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            String value = entry.getValue();
            if (key.isEmpty() || value == null || value.isBlank()) {
                continue;
            }
            if (!allKeys.contains(key)) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "覆盖段不存在：" + key + "（该模板版本没有此段，可能是旧版残留，请清理后再保存）");
            }
            // core 段禁止覆盖：这是 L3 的硬边界，不允许用「提交了但被忽略」糊过去
            if (!softKeys.contains(key)) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "覆盖段为锁定段：" + key + "（core 段不允许被 Agent 覆盖）");
            }
            if (value.length() > OVERRIDE_VALUE_MAX) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "覆盖段文案超过长度：" + key + "，最长 " + OVERRIDE_VALUE_MAX);
            }
            normalized.put(key, value);
        }
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return json.writeValueAsString(normalized);
        } catch (JsonProcessingException ex) {
            // 正常不会发生（键值都是字符串）；兜住以免以 500 暴露内部细节
            throw new BizException(ErrorCode.BAD_REQUEST, "段落覆盖序列化失败");
        }
    }

    /**
     * 段落快照（只为复用 soft 判定，避免在应用层重复 mutable 的位语义）。
     *
     * @param templateId 钉死版主键
     * @return 段落快照，按 sort_no 升序
     */
    private List<PromptSegmentSnapshot> segmentsOf(Long templateId) {
        List<PromptSegmentSnapshot> list = new ArrayList<>();
        for (PromptSegment segment : segments.listByTemplateId(templateId)) {
            list.add(new PromptSegmentSnapshot(segment.getSegKey(), segment.getDisplayName(),
                    segment.getSortNo(), segment.getMutable(), segment.getRequired(), segment.getContent()));
        }
        return list;
    }
}
