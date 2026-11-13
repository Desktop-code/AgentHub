package com.agenthub.agent.api.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改接待提示词的请求。所属域：agent。
 *
 * <p><b>已废止（刀4）</b>：接待人设只配在接待助手 Agent 自己的系统提示词里
 * （Agent 管理 → 接待助手）。该接口保留只是为了给旧前端 / 旧脚本一个明确失败，
 * 服务端固定返回 400，不再读取本字段。
 */
@Deprecated
@Data
public class ReceptionPromptReq {

    @Size(max = 12000, message = "接待提示词最长 12000 字符")
    private String prompt;
}
