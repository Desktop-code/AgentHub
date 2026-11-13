package com.agenthub.agent.domain.model;

import com.agenthub.agent.domain.service.AgentLookPalette;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.util.Locale;

/**
 * Agent 形象配置，JSON 落 {@code agent_def.avatar_config}。所属域：agent。
 * <p>
 * 两种形态：
 * <ul>
 *   <li>{@code preset}：{@code {type,icon,color}} —— 平台预置图 + 固定色板；</li>
 *   <li>{@code custom}：{@code {type,url}} —— 本站上传的图片，禁止外链。</li>
 * </ul>
 * 未配置时整列为 NULL，展示层用名称首字 + 默认主题紫；与用户头像字段完全分开。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentAvatarConfig {

    /** 预置形象 + 色板 */
    public static final String TYPE_PRESET = "preset";
    /** 自定义上传图 */
    public static final String TYPE_CUSTOM = "custom";

    private static final ObjectMapper JSON = new ObjectMapper();

    private String type;
    /** preset 时的预置键 */
    private String icon;
    /** preset 时的色板色值 */
    private String color;
    /** custom 时的本站相对 URL */
    private String url;

    /**
     * 从请求值构造并校验。全部为空时返回 null，表示清空配置。
     *
     * @param rawType  类型；空时按 url 是否为空推断
     * @param rawIcon  预置键
     * @param rawColor 色板色
     * @param rawUrl   自定义 URL
     * @return 规范化配置；全空为 null
     * @throws com.agenthub.common.error.BizException 键 / 色 / URL 非法
     */
    public static AgentAvatarConfig of(String rawType, String rawIcon, String rawColor, String rawUrl) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        String icon = rawIcon == null ? "" : rawIcon.trim();
        String color = rawColor == null ? "" : rawColor.trim();
        String type = rawType == null ? "" : rawType.trim().toLowerCase(Locale.ROOT);
        if (url.isEmpty() && icon.isEmpty() && color.isEmpty()) {
            return null;
        }
        // 显式 custom，或没给类型但带了 url：都按自定义图处理
        if (TYPE_CUSTOM.equals(type) || (type.isEmpty() && !url.isEmpty())) {
            AgentAvatarConfig custom = new AgentAvatarConfig();
            custom.setType(TYPE_CUSTOM);
            custom.setUrl(AgentLookPalette.normalizeAvatarUrl(url));
            return custom;
        }
        AgentAvatarConfig preset = new AgentAvatarConfig();
        preset.setType(TYPE_PRESET);
        preset.setIcon(AgentLookPalette.normalizeIcon(icon));
        preset.setColor(AgentLookPalette.normalizeColor(color));
        return preset;
    }

    /**
     * 解析库内 JSON。空值或脏值一律当作未配置，不让脏数据阻断详情页。
     *
     * @param json 库内原文
     * @return 配置；未配置 / 解析失败为 null
     */
    public static AgentAvatarConfig fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            AgentAvatarConfig config = JSON.readValue(json, AgentAvatarConfig.class);
            if (config == null || config.getType() == null || config.getType().isBlank()) {
                return null;
            }
            return config;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * @return 落库 JSON；无内容时 null
     */
    public String toJson() {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(this);
        } catch (Exception e) {
            // 本对象只含字符串，序列化失败属于编码错误，直接抛给上层暴露
            throw new IllegalStateException("Agent 形象配置序列化失败", e);
        }
    }

    /** @return 是否自定义上传图 */
    public boolean custom() {
        return TYPE_CUSTOM.equals(type) && url != null && !url.isBlank();
    }
}
