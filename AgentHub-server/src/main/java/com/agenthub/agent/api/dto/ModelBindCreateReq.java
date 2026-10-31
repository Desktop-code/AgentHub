package com.agenthub.agent.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增模型绑定。所属域：agent。用途可空，优先级默认 100。
 */
@Data
public class ModelBindCreateReq {

    @NotNull(message = "不能为空")
    private Long modelInstanceId;

    @Size(max = 32, message = "最长 32")
    private String purpose;

    @Min(value = 0, message = "不能小于 0")
    @Max(value = 10000, message = "不能大于 10000")
    private Integer priority;

    @Min(value = 0, message = "只能是 0 或 1")
    @Max(value = 1, message = "只能是 0 或 1")
    private Integer status;

    /** L3 提示词挂接（写 agent_model_bind 四列）；null=不挂模板 */
    private PromptBindingReq prompt;
}
