package com.agenthub.agent.api.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * L3 提示词配置出参（回显）。所属域：agent。
 * <p>
 * {@code override} 已解析为 Map 返回，前端直接绑定到各 soft 段输入框；
 * 库里的 JSON 解析失败属于脏数据，返回空 Map 并由页面提示，不在读取路径抛异常。
 */
@Data
public class PromptBindingResp {

    private Long templateId;
    private Integer templateVersion;
    private Map<String, String> override = new LinkedHashMap<>();
    private String promptExtra;
    /** 该配置用途是否支持挂模板（false 时前端隐藏整个区块） */
    private Boolean supported;
}
