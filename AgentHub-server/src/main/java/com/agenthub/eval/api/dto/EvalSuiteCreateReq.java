package com.agenthub.eval.api.dto;

import com.agenthub.eval.domain.model.EvalCaseSpec;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 新建套件入参。所属域：eval。
 * <p>cases 可空（先建空套件再导入），也可一次带全；带全时等同「版本 1 的全部用例」。
 * 校验细则（key 唯一、input.message 必填等）统一在 {@code EvalSpecs}，这里只挡空值和超长。
 */
@Data
public class EvalSuiteCreateReq {

    /** 租户内逻辑键，如 platform.smoke.rag；只允许字母数字与 . _ - */
    @NotBlank(message = "不能为空")
    @Size(max = 64, message = "最长 64")
    @JsonProperty("suite_key")
    @JsonAlias("suiteKey")
    private String suiteKey;

    /** 中文名 */
    @NotBlank(message = "不能为空")
    @Size(max = 128, message = "最长 128")
    private String name;

    @Size(max = 512, message = "最长 512")
    private String description;

    /** 默认配置 JSON（并发/超时/rule 开关），可空 */
    @JsonProperty("default_config")
    @JsonAlias("defaultConfig")
    private JsonNode defaultConfig;

    /** 1 启用 0 停用；空则 1 */
    private Integer enabled;

    /** 初始用例；可空 */
    private List<EvalCaseSpec> cases = new ArrayList<>();
}
