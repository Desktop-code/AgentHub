package com.agenthub.eval.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新套件元数据。所属域：eval。
 * <p>**只改元数据**：suite_key 不可改（改了等于换身份，历史版本引用会断），
 * cases 内容必须走 import 或单 case 写接口，那条路径会升版本。
 */
@Data
public class EvalSuiteUpdateReq {

    @NotBlank(message = "不能为空")
    @Size(max = 128, message = "最长 128")
    private String name;

    @Size(max = 512, message = "最长 512")
    private String description;

    /** 默认配置 JSON；传 null 表示清空 */
    @JsonProperty("default_config")
    @JsonAlias("defaultConfig")
    private JsonNode defaultConfig;

    /** 1 启用 0 停用；空则保持原值 */
    private Integer enabled;
}
