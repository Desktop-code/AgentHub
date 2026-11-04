package com.agenthub.eval.domain.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 套件 JSON 的结构化视图。所属域：eval。
 * <p>用途：POST /api/eval/suites/{id}/import 的请求体、classpath 平台样例的反序列化结果，
 * 两者共用同一结构（字段名对齐定稿 §5.2/§5.3）。
 * <p>JSON 块（default_config / input / expect / assertions / scoring）这里保留为 {@link JsonNode}，
 * 由 {@code EvalSpecs} 统一校验后再转成库内文本，避免各层各自序列化出不同形态。
 * <p>命名：对外的规范写法是 snake_case（suite_key / default_config），
 * 同时用 {@link JsonAlias} 兼容 camelCase，避免前端/脚本两种风格各写一份。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalSuiteSpec {

    /** 租户内逻辑键；导入到已有套件时可省略（取目标套件已有 key） */
    @JsonProperty("suite_key")
    @JsonAlias("suiteKey")
    private String suiteKey;
    /** 中文名；可省略表示沿用目标套件名 */
    private String name;
    /** 说明；可省略 */
    private String description;
    /** 语言，默认 zh-CN；本刀只落进 default_config，不做 i18n */
    private String locale;
    /** 默认配置 JSON（对齐定稿 §5.3 子集） */
    @JsonProperty("default_config")
    @JsonAlias("defaultConfig")
    private JsonNode defaultConfig;
    /** 用例数组；导入时即新版本的全部用例 */
    private List<EvalCaseSpec> cases = new ArrayList<>();
}
