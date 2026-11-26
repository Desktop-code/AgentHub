package com.agenthub.workflow.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 调试抽屉按 run 回复。所属域：workflow 的 api。
 * <p>正文写入同一条试跑的 {@code input.reply}，不新建 run。
 */
@Data
public class WorkflowRunReplyReq {

    /** 用户下一句，或点选的选项文案 */
    @NotBlank(message = "回复内容不能为空")
    @Size(max = 4000, message = "回复内容过长")
    private String reply;
}
