package com.agenthub.runtime.api.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 工作台「点选可点选项 / 自填答复」请求。所属域：runtime（api）。
 *
 * <p>两种提交共用一条断点续作语义，区别只在用户侧文本从哪来：
 * <ul>
 *   <li><b>普通点选</b>：只传 {@code optionId}；展示文案以服务端上一轮落库的选项块为准，
 *       不采信客户端文案，避免借这个接口把任意文本塞进模型上下文。</li>
 *   <li><b>自填答复</b>：平台固定的「其它，我自己说」行没有模型 option id，
 *       因此允许 {@code optionId} 为空，由 {@code text} 携带用户自己写下的答复。</li>
 * </ul>
 *
 * <p>与「发一句新问题」的区别：本请求走断点续作，不重新判路由。
 */
@Data
public class WorkbenchChoiceReq {

    /** 上一轮可点选项的 id；自填答复时可为空（平台自填行没有模型 option id） */
    @Size(max = 64, message = "选项标识过长")
    private String optionId;

    /**
     * 用户自填的答复原文；为空表示普通点选。
     *
     * <p>长度上限由服务端按 Unicode 码点校验，与前端自填校验同口径，
     * 不在 DTO 用 {@code @Size}（那是 UTF-16 长度，会把表情算成两个字符）。
     */
    private String text;
}
