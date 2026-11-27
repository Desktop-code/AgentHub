package com.agenthub.agent.domain.service;

import com.agenthub.common.error.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Agent 入参声明归一的单测。所属域：agent 的 domain。
 * <p>钉死：必填只保留 properties 里真的存在的键；空 properties = 清空；脏结构保存即拒。
 */
class AgentInputSchemaTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("归一后只留 title / description / required，值类型固定 string")
    void normalizeKeepsKnownFields() throws Exception {
        JsonNode raw = json.readTree("{\"type\":\"object\",\"properties\":{"
                + "\"topic\":{\"type\":\"integer\",\"title\":\"主题\",\"description\":\"写什么\"},"
                + "\"tone\":{\"title\":\"  \"},\"ghost\":{\"x\":1}},"
                + "\"required\":[\"topic\",\"ghost\",\"missing\"]}");

        JsonNode stored = json.readTree(AgentInputSchema.normalize(raw));

        assertThat(stored.get("type").asText()).isEqualTo("object");
        assertThat(stored.get("properties").get("topic").get("type").asText()).isEqualTo("string");
        assertThat(stored.get("properties").get("topic").get("title").asText()).isEqualTo("主题");
        assertThat(stored.get("properties").get("topic").get("description").asText()).isEqualTo("写什么");
        // 空白展示名不落库，避免页面出现空标题
        assertThat(stored.get("properties").get("tone").has("title")).isFalse();
        // required 必须与 properties 取交集，missing 这种「没槽可配」的键不能留
        List<String> required = new ArrayList<>();
        stored.get("required").forEach(item -> required.add(item.asText()));
        assertThat(required).containsExactly("topic", "ghost");
    }

    @Test
    @DisplayName("没有声明 / 空 properties：归一成 null，表示清空")
    void emptyDeclarationIsNull() throws Exception {
        assertThat(AgentInputSchema.normalize(null)).isNull();
        assertThat(AgentInputSchema.normalize(json.readTree("{\"properties\":{}}"))).isNull();
        assertThat(AgentInputSchema.normalize(json.readTree("{}"))).isNull();
    }

    @Test
    @DisplayName("脏结构保存即拒：非对象、键为空、字段定义不是对象、展示名超长")
    void invalidDeclarationRejected() throws Exception {
        assertThatThrownBy(() -> AgentInputSchema.normalize(json.readTree("[]")))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> AgentInputSchema.normalize(json.readTree("{\"properties\":{\" \":{}}}")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("键不能为空");
        assertThatThrownBy(() -> AgentInputSchema.normalize(json.readTree("{\"properties\":{\"a\":\"x\"}}")))
                .isInstanceOf(BizException.class);

        // required 不是数组属于「没写清」，不拦保存：按无必填处理
        assertThatCode(() -> AgentInputSchema.normalize(json.readTree(
                "{\"properties\":{\"a\":{\"title\":\"x\"}},\"required\":\"a\"}")))
                .doesNotThrowAnyException();

        String longTitle = "x".repeat(80);
        assertThatThrownBy(() -> AgentInputSchema.normalize(json.readTree(
                "{\"properties\":{\"a\":{\"title\":\"" + longTitle + "\"}}}")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("展示名");
    }

    @Test
    @DisplayName("回显：坏 JSON / 缺 properties 按没有声明处理，不抛异常")
    void readToleratesBadData() {
        assertThat(AgentInputSchema.read(null)).isNull();
        assertThat(AgentInputSchema.read("not json")).isNull();
        assertThat(AgentInputSchema.read("{\"type\":\"object\"}")).isNull();
        assertThat(AgentInputSchema.read("{\"properties\":{\"a\":{}}}")).isNotNull();
    }
}
