package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.model.GeneratedFile;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 给 Toolkit 套上 {@link FileMarkerAgentTool}。所属域：runtime 的 infra。
 * 应包在配额与必填闸门之外、交给引擎之前调用，这样任何工具（内置或 MCP）产出的
 * 「文件已生成」标记都会被提取并剔除，模型上下文与聊天框都不会看到那段机械字符。
 */
public final class ToolkitFileMarker {

    private ToolkitFileMarker() {
    }

    /**
     * 原地替换为带标记拦截的包装。无工具时原样返回。
     *
     * <p>出口为空也照常包装：剔除标记这件事本身就有价值——
     * 即便没有前端要收产物，模型上下文也不该被这段字符污染。
     *
     * @param toolkit 已装配工具集
     * @param sink    产物出口，可空；非空时每个提取到的文件都会被交给它
     *                （{@link GeneratedFileSink} 或 {@code progress::file} 均可）
     * @return 同一 toolkit
     */
    public static Toolkit wrap(Toolkit toolkit, Consumer<GeneratedFile> sink) {
        if (toolkit == null) {
            return toolkit;
        }
        Set<String> names = new HashSet<>(toolkit.getToolNames());
        for (String name : names) {
            AgentTool original = toolkit.getTool(name);
            if (original == null || original instanceof FileMarkerAgentTool) {
                continue;
            }
            toolkit.removeTool(name);
            toolkit.registerAgentTool(new FileMarkerAgentTool(original, sink));
        }
        return toolkit;
    }
}
