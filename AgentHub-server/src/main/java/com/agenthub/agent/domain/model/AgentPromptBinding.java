package com.agenthub.agent.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * L3 提示词挂载值（已校验、可入库的形态）。所属域：agent。
 * <p>
 * 对应 {@code agent_def} / {@code agent_model_bind} 上的四列：
 * {@code prompt_template_id}、{@code template_version}、{@code prompt_override_json}、{@code prompt_extra}。
 * <p>
 * 约束：
 * 1. {@code templateId} 为空时其余三项只能是「空覆盖 + 可选追加区」，不允许只有版本或只有覆盖；
 * 2. {@code overrideJson} 是**规范化后**的 JSON（仅 soft 段的非空值），空白即表示无覆盖，存 null；
 * 3. 本类只承载引用与文本，不含任何模型的 Key / Token。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentPromptBinding {

    /** 钉死的 L2 模板主键；空=未挂模板 */
    private Long templateId;
    /** 钉死的 L2 版本号；与 templateId 同时有值 */
    private Integer templateVersion;
    /** L3 soft 覆盖 JSON：seg_key → 文案；无覆盖为 null */
    private String overrideJson;
    /** L3 追加区（append-only）；可空 */
    private String promptExtra;

    /**
     * 未挂模板的空绑定。用于「清空 L3 配置」与新建默认值。
     *
     * @return 四项全空的绑定
     */
    public static AgentPromptBinding empty() {
        return new AgentPromptBinding(null, null, null, null);
    }
}
