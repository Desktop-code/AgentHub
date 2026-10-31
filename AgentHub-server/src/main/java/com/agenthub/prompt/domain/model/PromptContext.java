package com.agenthub.prompt.domain.model;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 运行时装填上下文。所属域：prompt。
 * <p>
 * 职责：把「这一次模型调用要用哪套提示词」压成一个与用途无关的结构，
 * 由 CHAT / 非 CHAT 两个 Adapter 分别从 {@code agent_def}、{@code agent_model_bind} 填，
 * {@link com.agenthub.prompt.application.PromptAssembler} 只认本类，不再区分调用方。
 * <p>
 * 约束：
 * 1. 本类只放「配置与引用」，不放段落正文；正文由 Assembler 按缓存/DB 取。
 * 2. {@code templateId/templateVersion} 为空表示「未钉死」，按 purpose 取生效版；
 *    两者都有且与库中行不一致时按配置错误阻断。
 * 3. {@code businessSegment} 是 {@code agent_def.system_prompt} 的历史用户业务段，
 *    在 L2 之后、{@code promptExtra} 之前以「业务提示词」标记追加，保证老配置不丢。
 * 4. 任何 Key / Token 不得放进本类，本类仅承载文本与引用。
 */
@Data
@Builder(toBuilder = true)
public class PromptContext {

    /** 用途码，取值见 {@link com.agenthub.prompt.domain.enums.PromptPurpose}；必填 */
    private String purpose;
    /** 语言；空白回落 zh-CN */
    private String locale;
    /** 钉死的模板主键；可空 */
    private Long templateId;
    /** 模板业务键；可空，为空时按 purpose 取生效版 */
    private String templateKey;
    /** 钉死的模板版本；可空，与 templateId 一起用于校验版本漂移 */
    private Integer templateVersion;
    /** L3 soft 覆盖 JSON：seg_key → 文案；可空 */
    private String overrideJson;
    /** L3 追加区，append-only；可空 */
    private String promptExtra;
    /**
     * 平台侧追加句（非用户可编辑），紧跟 L1 之后、L2 之前。
     * 目前只有 ROUTER 用它带「你是路由型助手…」这句平台路由说明，
     * 使 L1 在路由场景下的语义完整；值来自平台资源，Agent 无法修改。
     */
    private String constraintExtra;
    /** 历史用户业务段（agent_def.system_prompt），L2 之后追加；可空 */
    private String businessSegment;
    /** Agent 名称，供 {{agent_name}} 变量；可空 */
    private String agentName;
    /** 本轮用户原文，供 {{user_query}} 变量；可空 */
    private String userQuery;
    /** 额外变量（如测试注入固定 current_time）；不得覆盖白名单之外的键 */
    @Builder.Default
    private Map<String, String> variables = new HashMap<>();
    /** 字符预算；<=0 表示用平台配置 */
    private int maxChars;
}
