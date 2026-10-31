package com.agenthub.memory.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 新建或编辑记忆。所属域：memory。正文在服务层再拦密钥。
 */
@Data
public class MemoryItemReq {

    private Long agentId;
    private Long userId;
    private Long appId;
    private Long sessionId;

    /** 新建必填。编辑时忽略，范围不允许改。 */
    private String scope;

    @Size(max = 128, message = "标题最长 128")
    private String title;

    /** LONG 稳定事实键；可空。填了则同键合并；须小写 snake_case 或可归一同义词 */
    @Size(max = 64, message = "fact_key 最长 64")
    private String factKey;

    @NotBlank(message = "记忆正文不能为空")
    @Size(max = 8000, message = "记忆正文最长 8000")
    private String content;

    private Integer importance;
    private Integer status;
    private LocalDateTime expiredTime;

    @Size(max = 500, message = "备注最长 500")
    private String remark;
}
