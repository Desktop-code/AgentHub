package com.agenthub.admin.domain.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 调用方应用。所属域：admin。这是入站身份：业务系统调用 AgentHub。
 * 密钥只存哈希，不能解密还原；明文只在创建或重置时由应用服务单独返回。
 * 模型出站 Key 在 model 域用 AES-GCM 保存，两者不要混用。
 */
@Data
public class App {

    private Long id;
    private String appKey;
    private String name;
    private Long deptId;
    private String deptName;
    private Integer status;
    private String remark;
    private Long createdBy;
    private LocalDateTime createTime;
    private List<Long> agentIds = new ArrayList<>();
}
