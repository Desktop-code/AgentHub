package com.agenthub.runtime.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 工作台「点选可点选项」请求。所属域：runtime（api）。
 *
 * <p>只接收选项标识，<b>不</b>接收展示文案：文案以服务端上一轮落库的选项块为准，
 * 避免客户端借这个接口把任意文本塞进模型上下文。
 *
 * <p>与「发一句新问题」的区别：本请求走断点续作，不重新判路由。
 */
@Data
public class WorkbenchChoiceReq {

    /** 上一轮 {@code agenthub-choice} 块里的选项 id；必须在该块内存在 */
    @NotBlank(message = "请选择一个选项")
    @Size(max = 64, message = "选项标识过长")
    private String optionId;
}
