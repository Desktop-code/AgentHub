package com.agenthub.agent.api.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 多智能体方案详情。所属域：agent。
 *
 * <p>列表字段 + 与 Agent 运行配置同构的 config + 候选列表；
 * 编辑抽屉一次请求即可回填，不再拆多次查询。
 */
@Data
public class MaDefDetailResp {

    private Long id;
    private String code;
    private String name;
    /** 方案说明：作为 MA 候选的意图说明单一真源 */
    private String description;
    private String template;
    /** 与 AgentRuntimeConfigResp 同构的编排配置 */
    private AgentRuntimeConfigResp config;
    /** 方案自有形象；null＝未配置（前端兜底） */
    private AgentAvatarConfigDto avatarConfig;
    /** 历史绑根字段：已停用，恒为 null（列保留不 DROP） */
    private Long rootAgentId;
    private String rootAgentName;
    private Integer enabled;
    private Integer version;
    private List<MaCandidateResp> candidates;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
