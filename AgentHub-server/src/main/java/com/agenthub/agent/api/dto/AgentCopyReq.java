package com.agenthub.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 复制 Agent。所属域：agent。
 * <p>
 * 只允许改名称与编码：其余配置（运行模式、运行限制、编排策略、CHAT 的 L3 四列）
 * 全部由服务端从源 Agent 继承，避免前端漏传字段导致复制品配置残缺。
 */
@Data
public class AgentCopyReq {

    /** 复制品名称；未删除范围内不要求唯一，与新建口径一致 */
    @NotBlank(message = "不能为空")
    @Size(max = 64, message = "最长 64")
    private String name;

    /** 复制品编码；未删除范围内必须唯一，冲突时由服务端拒绝 */
    @NotBlank(message = "不能为空")
    @Size(max = 64, message = "最长 64")
    private String code;
}
