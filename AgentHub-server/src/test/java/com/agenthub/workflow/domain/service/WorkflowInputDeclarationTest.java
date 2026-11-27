package com.agenthub.workflow.domain.service;

import com.agenthub.workflow.application.WorkflowGraphCodec;
import com.agenthub.workflow.domain.model.WorkflowGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入参声明投影单测。所属域：workflow 的 domain。
 * <p>钉死两件事：声明只认 START 节点的 config.inputSchema；必填键必须同时出现在 properties，
 * 否则会出现「有必填名字但没有槽可配」的死配置。
 */
class WorkflowInputDeclarationTest {

    private final WorkflowGraphCodec codec = new WorkflowGraphCodec(new ObjectMapper());

    @Test
    @DisplayName("只从 START 节点读声明；AGENT 上的同名字段不算")
    void readsDeclarationFromStartOnly() {
        String json = "{\"nodes\":["
                + "{\"id\":\"n1\",\"code\":\"start\",\"type\":\"START\",\"config\":{\"inputSchema\":"
                + "{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"string\",\"title\":\"主题\"}},"
                + "\"required\":[\"topic\"]}}},"
                + "{\"id\":\"n2\",\"code\":\"end\",\"type\":\"END\",\"config\":{\"inputSchema\":"
                + "{\"type\":\"object\",\"properties\":{\"ignored\":{\"type\":\"string\"}}}}}"
                + "],\"edges\":[]}";
        WorkflowGraph graph = codec.parse(json);

        var declaration = WorkflowInputDeclaration.startDeclaration(graph);

        assertThat(declaration).isNotNull();
        assertThat(declaration.get("properties").has("topic")).isTrue();
        assertThat(WorkflowInputDeclaration.requiredKeys(declaration)).containsExactly("topic");
    }

    @Test
    @DisplayName("没有 START / 没有声明 / 声明不是对象：一律返回 null")
    void missingDeclarationIsNull() {
        assertThat(WorkflowInputDeclaration.startDeclaration(null)).isNull();
        assertThat(WorkflowInputDeclaration.startDeclaration(codec.parse(
                codec.defaultGraph()))).isNull();
        assertThat(WorkflowInputDeclaration.startDeclaration(codec.parse(
                "{\"nodes\":[{\"id\":\"n1\",\"code\":\"s\",\"type\":\"START\","
                        + "\"config\":{\"inputSchema\":\"bad\"}}],\"edges\":[]}"))).isNull();
    }

    @Test
    @DisplayName("必填键只取 properties ∩ required，并按 required 顺序去重")
    void requiredKeysIntersectProperties() {
        var declaration = WorkflowInputDeclaration.parse(
                "{\"type\":\"object\",\"properties\":{\"a\":{},\"b\":{}},"
                        + "\"required\":[\"b\",\"ghost\",\"b\",\"a\"]}");

        assertThat(WorkflowInputDeclaration.requiredKeys(declaration)).containsExactly("b", "a");
        assertThat(WorkflowInputDeclaration.requiredKeys(null)).isEmpty();
        assertThat(WorkflowInputDeclaration.parse("not json")).isNull();
    }
}
