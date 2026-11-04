package com.agenthub.eval.domain.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 单条用例 JSON 的结构化视图。所属域：eval。
 * <p>字段与定稿 §5.2 一一对应；本刀只做存储与回读，断言与打分**不执行**。
 * <p>命名：对外规范写法为 snake_case（case_key / sort_order），同时兼容 camelCase。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalCaseSpec {

    /** 版本内逻辑键，必填 */
    @JsonProperty("case_key")
    @JsonAlias("caseKey")
    private String caseKey;
    /** 标题，必填 */
    private String title;
    /** 标签 */
    private List<String> tags = new ArrayList<>();
    /** 展示与执行序 */
    @JsonProperty("sort_order")
    @JsonAlias("sortOrder")
    private Integer sortOrder;
    /**
     * 1 启用 0 停用；空则 1。
     * <p>业务 JSON 常写布尔 true/false；必须用宽松反序列化，否则 Controller 读体会被当成「JSON 格式错」。
     */
    @JsonDeserialize(using = EvalEnabledDeserializer.class)
    private Integer enabled;
    /** 输入，必填（至少要能取到 message） */
    private JsonNode input;
    /** 期望，可空 */
    private JsonNode expect;
    /** 断言列表，可空（本刀只存） */
    private JsonNode assertions;
    /** 打分覆盖，可空（本刀只存） */
    private JsonNode scoring;
}
