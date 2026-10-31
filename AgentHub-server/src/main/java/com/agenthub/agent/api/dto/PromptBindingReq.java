package com.agenthub.agent.api.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * L3 提示词配置入参（CHAT 与非 CHAT 共用）。所属域：agent。
 * <p>
 * 三项一起提交：只给版本不给模板 id、或只给覆盖不给模板，都会在后端被拒绝，
 * 因此前端应当整体回传（未挂模板时全为空）。
 */
@Data
public class PromptBindingReq {

    /** 钉死的 L2 模板主键；null 表示不挂模板 */
    private Long templateId;

    /** 钉死的 L2 版本号；templateId 非空时必填 */
    private Integer templateVersion;

    /**
     * soft 段覆盖：seg_key → 文案。
     * 键必须落在该钉死版 {@code mutable=1} 的段里；含 core 段或未知段一律拒绝保存。
     */
    private Map<String, String> override = new LinkedHashMap<>();

    /** 追加区（append-only）；不能覆盖锁定段。非 CHAT 可留空 */
    @Size(max = 12000, message = "提示词追加区超过长度，最长 12000")
    private String promptExtra;
}
