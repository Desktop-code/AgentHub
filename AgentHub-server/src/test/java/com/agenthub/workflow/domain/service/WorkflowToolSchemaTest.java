package com.agenthub.workflow.domain.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具 schema 必填投影单测。所属域：workflow 的 domain。
 * <p>口径必须与前端出槽一致：只取顶层 properties ∩ required，坏 JSON 不抛异常。
 */
class WorkflowToolSchemaTest {

    @Test
    @DisplayName("顶层 required 与 properties 取交集，嵌套对象不展开")
    void requiredIntersectsTopLevelProperties() {
        String schema = "{\"type\":\"object\",\"properties\":{"
                + "\"city\":{\"type\":\"string\"},"
                + "\"days\":{\"type\":\"integer\"},"
                + "\"filter\":{\"type\":\"object\",\"properties\":{\"keyword\":{\"type\":\"string\"}}}"
                + "},\"required\":[\"city\",\"ghost\",\"city\",7]}";

        assertThat(WorkflowToolSchema.requiredInputs(schema)).containsExactly("city");
    }

    @Test
    @DisplayName("空 / 非法 / 没有 required 的 schema 返回空列表，不抛异常")
    void invalidSchemaYieldsEmpty() {
        assertThat(WorkflowToolSchema.requiredInputs(null)).isEmpty();
        assertThat(WorkflowToolSchema.requiredInputs("  ")).isEmpty();
        assertThat(WorkflowToolSchema.requiredInputs("not json")).isEmpty();
        assertThat(WorkflowToolSchema.requiredInputs("[]")).isEmpty();
        assertThat(WorkflowToolSchema.requiredInputs("{\"type\":\"object\",\"properties\":{}}")).isEmpty();
        assertThat(WorkflowToolSchema.requiredInputs(
                "{\"properties\":{\"city\":{}},\"required\":\"city\"}")).isEmpty();
    }
}
