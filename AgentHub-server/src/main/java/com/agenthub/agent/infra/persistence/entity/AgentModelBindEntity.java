package com.agenthub.agent.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * agent_model_bind。所属域：agent。无物理外键，model_instance_id 由应用层校验。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_model_bind")
public class AgentModelBindEntity extends AgentAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private Long modelInstanceId;
    private String purpose;
    /** 非 CHAT 用途的 L2 模板主键；L3 挂载点 */
    private Long promptTemplateId;
    /** 钉死的 L2 版本号 */
    private Integer templateVersion;
    /** L3 soft 覆盖 JSON */
    private String promptOverrideJson;
    /** L3 追加区，可空 */
    private String promptExtra;
    private Integer priority;
    private Integer status;

    @TableLogic
    private Integer deleted;
}
