package com.agenthub.workflow.domain.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * TOOL 节点执行口。所属域：workflow 的 domain。
 * <p>P0 只接 MCP 工具源（现网已有 tools/call 通道）；BUILTIN 工具的「跳出模型直接调」
 * 需要反射 @Tool 方法，本刀不做，实现方按「未接线」报中文失败。
 * <p>失败抛 {@link com.agenthub.workflow.domain.WorkflowNodeFailure}，禁止把鉴权头写进文案。
 */
public interface WorkflowToolInvoker {

    /**
     * 同步调用一次工具。
     *
     * @param toolId 工具项目 id
     * @param args   实参对象（由 inputMapping 解析而来，可为空对象）
     * @return 提供给下游节点的文本结果；实现方保证非空
     */
    String invoke(Long toolId, JsonNode args);
}
