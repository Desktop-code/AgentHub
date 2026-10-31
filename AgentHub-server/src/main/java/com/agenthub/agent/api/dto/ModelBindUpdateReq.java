package com.agenthub.agent.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改模型绑定。所属域：agent。不改实例，只改用途、优先级和启停。
 * <p>
 * 非 CHAT 用途的 L3 提示词（ROUTER / REWRITE / SUMMARY / MEMORY）随本请求一起提交：
 * 传 null 表示不动现有值，传空对象表示清空模板与覆盖。
 */
@Data
public class ModelBindUpdateReq {

    @Size(max = 32, message = "最长 32")
    private String purpose;

    @NotNull(message = "不能为空")
    @Min(value = 0, message = "不能小于 0")
    @Max(value = 10000, message = "不能大于 10000")
    private Integer priority;

    @NotNull(message = "不能为空")
    @Min(value = 0, message = "只能是 0 或 1")
    @Max(value = 1, message = "只能是 0 或 1")
    private Integer status;

    /** L3 提示词挂接（写 agent_model_bind 四列）；null=不修改 */
    private PromptBindingReq prompt;
}
