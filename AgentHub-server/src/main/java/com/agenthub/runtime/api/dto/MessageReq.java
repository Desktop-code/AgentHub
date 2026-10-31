package com.agenthub.runtime.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 试跑一轮的用户输入。所属域：runtime。正文不进操作日志。
 */
@Data
public class MessageReq {

    @NotBlank(message = "消息不能为空")
    @Size(max = 8000, message = "消息不能超过 8000 字")
    private String content;
}
