package com.agenthub.agent.api.dto;

import com.agenthub.agent.domain.model.AgentAvatarConfig;
import lombok.Data;

/**
 * Agent 形象配置的请求 / 响应体。所属域：agent。
 * <p>
 * preset：{@code type=preset, icon=look-guide, color=#635BFF}；
 * custom：{@code type=custom, url=/uploads/agent-avatars/...}；
 * 全部字段为空表示「未配置」（响应里整个对象为 null）。
 */
@Data
public class AgentAvatarConfigDto {

    /** preset / custom */
    private String type;
    /** 预置键；custom 时为空 */
    private String icon;
    /** 色板色值；custom 时为空 */
    private String color;
    /** 自定义头像相对 URL；preset 时为空 */
    private String url;

    /**
     * 库内 JSON → 响应体。脏值按未配置处理。
     *
     * @param json 库内原文
     * @return 响应体；未配置时 null
     */
    public static AgentAvatarConfigDto fromJson(String json) {
        AgentAvatarConfig config = AgentAvatarConfig.fromJson(json);
        if (config == null) {
            return null;
        }
        AgentAvatarConfigDto dto = new AgentAvatarConfigDto();
        dto.setType(config.getType());
        dto.setIcon(config.getIcon());
        dto.setColor(config.getColor());
        dto.setUrl(config.getUrl());
        return dto;
    }
}
