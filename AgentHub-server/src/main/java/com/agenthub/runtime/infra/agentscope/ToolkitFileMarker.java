package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.model.GeneratedFile;
import com.agenthub.runtime.domain.model.PromptInjectionStats;
import com.agenthub.runtime.domain.service.ToolResultOffloadPolicy;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 给 Toolkit 套上 {@link FileMarkerAgentTool}。所属域：runtime 的 infra。
 * 应包在配额与必填闸门之外、交给引擎之前调用，这样任何工具（内置或 MCP）产出的
 * 「文件已生成」标记都会被提取并剔除，模型上下文与聊天框都不会看到那段机械字符；
 * 同时超长结果会在这里落盘成「摘要 + 路径」。
 */
public final class ToolkitFileMarker {

    private ToolkitFileMarker() {
    }

    /**
     * 只做标记剔除（不启用句柄化）。
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
        return wrap(toolkit, sink, null, 0, null);
    }

    /**
     * 标记剔除 + 超长结果句柄化。
     *
     * @param toolkit         已装配工具集
     * @param sink            产物出口，可空
     * @param offload         落盘器，可空；空则不做句柄化
     * @param offloadMaxChars 落盘阈值（字符）；≤0 关闭
     * @param stats           本轮注入统计，可空；非空时记录落盘条数与原始字符数
     * @return 同一 toolkit
     */
    public static Toolkit wrap(Toolkit toolkit, Consumer<GeneratedFile> sink,
                               ToolResultOffloadStore offload, int offloadMaxChars,
                               PromptInjectionStats stats) {
        if (toolkit == null) {
            return toolkit;
        }
        // 先收集名字再替换，避免遍历期间改 registry
        Set<String> names = new HashSet<>(toolkit.getToolNames());
        /*
         * 读文件工具必须从「本轮真实注册」的名字里取：检索类专家通常只绑了检索工具，
         * 若摘要里照样写「请调用 workspaceReadFile」，模型会调一个不存在的工具，
         * 失败几次就把自己熔断掉。取不到就返回 null，落盘侧据此不落盘、不点名。
         */
        String readToolName = ToolResultOffloadPolicy.readToolIn(names);
        for (String name : names) {
            AgentTool original = toolkit.getTool(name);
            if (original == null || original instanceof FileMarkerAgentTool) {
                continue;
            }
            toolkit.removeTool(name);
            toolkit.registerAgentTool(new FileMarkerAgentTool(original, sink, offload, offloadMaxChars, stats,
                    readToolName));
        }
        return toolkit;
    }
}
