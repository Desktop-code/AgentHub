package com.agenthub.agent.api.dto;

import lombok.Data;

/**
 * Skill 绑定行。所属域：agent。不含 contentMd。
 */
@Data
public class SkillBindResp {

    private Long id;
    private Long agentId;
    private Long skillId;
    private String name;
    private String title;
    private Integer status;
    /** Skill 当前启停；Skill 已删时为空 */
    private Integer targetStatus;
    /** ROUTER 绑得过多时的保存警告 */
    private String warning;
}
