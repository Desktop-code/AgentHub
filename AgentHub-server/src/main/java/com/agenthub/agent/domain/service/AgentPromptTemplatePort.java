package com.agenthub.agent.domain.service;

import com.agenthub.agent.domain.model.AgentPromptBinding;
import com.agenthub.agent.domain.model.AgentPromptPreviewResult;
import com.agenthub.agent.domain.model.AgentPromptTemplateOption;
import com.agenthub.agent.domain.model.AgentPromptTemplateView;

import java.util.List;
import java.util.Map;

/**
 * L3 提示词挂接端口。所属域：agent（仅接口）。
 * <p>
 * 为什么在 agent 域只留接口：模板段落、版本校验与组装都属于 prompt 域，
 * 而 agent 域要写 {@code agent_def} / {@code agent_model_bind} 的四列。
 * 若让 agent 直接依赖 prompt 实现，prompt 的 Adapter 又要读 AgentDef，两个域会循环引用。
 * 依赖方向固定为 prompt → agent，本接口的实现落在
 * {@code com.agenthub.prompt.application.AgentPromptBindingService}。
 * <p>
 * 调用方：{@code AgentAppService}（CHAT，写 agent_def）与
 * {@code AgentModelBindAppService}（非 CHAT，写 agent_model_bind），以及配置页的模板读取与预览接口。
 * <p>
 * 失败语义：非法段键、版本漂移、模板用途不符一律抛 {@code BizException}（业务码 400/409），
 * **不得**静默丢弃——静默丢弃会让配置者误以为改过的段已生效。
 * 线程安全：实现须无状态、可并发调用。
 */
public interface AgentPromptTemplatePort {

    /** CHAT 用途码。agent_def 上的 L3 四列只允许挂本用途的模板 */
    String PURPOSE_CHAT = "CHAT";

    /**
     * 该用途是否已有提示词模板维度（即 {@code PromptPurpose} 覆盖的用途）。
     * 前端据此决定模型槽是否出现「提示词」入口；VISION / ASR 等非生成槽返回 false。
     *
     * @param purpose 用途码，可空
     * @return true 表示可挂 L2 模板
     */
    boolean supportsPurpose(String purpose);

    /**
     * 列出该用途**已发布**的模板版本，供下拉选择。草稿不出现，避免绑到永不生效的版本。
     *
     * @param purpose 用途码
     * @param locale  语言；空白回落 zh-CN
     * @return 版本行，按版本倒序；用途不支持时为空列表
     */
    List<AgentPromptTemplateOption> publishedOptions(String purpose, String locale);

    /**
     * 取某个已发布版本行及其段落，供 L3 表单按 mutable 分栏渲染。
     *
     * @param templateId 版本行主键
     * @return 模板视图；不存在或为草稿时抛业务异常（配置页不能显示空表单当成功）
     */
    AgentPromptTemplateView view(Long templateId);

    /**
     * 校验并规范化四列取值（保存硬闸）。
     * <p>
     * 规则：
     * <ol>
     *   <li>未选模板：版本/覆盖必须为空，追加区可留；</li>
     *   <li>选了模板：版本必填且必须等于该模板行的 version；模板用途必须与 {@code purpose} 一致；</li>
     *   <li>覆盖键集合 ⊆ 该钉死版 {@code mutable=1} 的 seg_key；含 core 段或未知段 → 拒绝；</li>
     *   <li>空白值视为「未覆盖」，不写入 JSON，避免用空串把 soft 段整段抹掉。</li>
     * </ol>
     *
     * @param purpose         期望用途码
     * @param templateId      模板主键，可空
     * @param templateVersion 钉死版本，与 templateId 同时有值
     * @param override        seg_key → 文案；可空
     * @param promptExtra     追加区；可空
     * @return 可直接入库的绑定值
     * @throws com.agenthub.common.error.BizException 任一硬闸不通过
     */
    AgentPromptBinding normalize(String purpose, Long templateId, Integer templateVersion,
                                 Map<String, String> override, String promptExtra);

    /**
     * 读路径解析库里的 {@code prompt_override_json}。
     * <p>
     * 与保存路径不同，这里**不抛异常**：历史脏值（非法 JSON / 值不是字符串）不应让整个详情页打不开。
     * 无法解析时返回空 Map，由页面提示「覆盖已失效，请重新保存」。
     *
     * @param overrideJson 库内 JSON，可空
     * @return seg_key → 文案；无覆盖或不可解析时为空 Map
     */
    Map<String, String> readOverrideSafe(String overrideJson);

    /**
     * 预览组装结果。内部走与出站完全相同的 {@code PromptAssembler.assemble}，不调用模型。
     *
     * @param purpose         用途码
     * @param locale          语言；空白回落 zh-CN
     * @param templateId      模板主键，必填
     * @param templateVersion 钉死版本，必填
     * @param override        seg_key → 文案；可空
     * @param promptExtra     追加区；可空
     * @param agentName       Agent 名称，供 {{agent_name}}；可空
     * @param userQuery       测试用用户原文，供 {{user_query}}；可空
     * @return 正文与版本统计
     * @throws com.agenthub.common.error.BizException 缺 L1 / 缺模板 / 缺必选段 / 非法段键
     */
    AgentPromptPreviewResult preview(String purpose, String locale, Long templateId, Integer templateVersion,
                                     Map<String, String> override, String promptExtra,
                                     String agentName, String userQuery);
}
