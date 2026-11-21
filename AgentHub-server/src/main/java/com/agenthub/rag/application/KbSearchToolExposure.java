package com.agenthub.rag.application;

import com.agenthub.rag.domain.model.RagKnowledgeBase;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 决定内置检索工具本轮是否交给模型，以及工具说明里的知识库清单。所属域：rag。
 * <p>
 * 为什么要有这一层：工具一旦注册进 Toolkit，模型就会看到并按需调用；若没有可用绑定库，
 * 模型照样会调一次，拿到 {@link RagSearchTool#NO_BINDING} 后空跑一轮。
 * 因此「有没有可用库」必须在<b>装配阶段</b>判断一次，没有就整条不交出。
 * <p>
 * 判据只复用现网真源 {@link AgentKbBindAppService#listUsableForAgent}（绑定启用 + 库可检索），
 * 不另造一套「已绑定」口径；本类只做取舍与文案，不改选库、改写、召回任何一环。
 * <p>
 * 库清单随装配即时生成：库名与描述来自当前的 {@code rag_knowledge_base} 行，
 * 不写死在 {@code @Tool} 注解里，也不允许模型通过入参点名库（参数仍只有 query / topK）。
 */
@Component
public class KbSearchToolExposure {

    /** 单库描述在工具说明里的字数上限：描述是「何时查这个库」的判据，不是正文，过长只挤 token */
    private static final int KB_DESCRIPTION_MAX = 100;

    /** 空描述的固定占位：既不假装有说明，也不因此把该库从清单里拿掉 */
    static final String NO_DESCRIPTION = "（尚无描述）";

    /** 库名兜底：名称列为 NOT NULL，但历史脏数据可能只有空格 */
    private static final String UNNAMED = "（未命名知识库）";

    private final AgentKbBindAppService binds;

    /**
     * @param binds Agent 知识库绑定（取可用库；与检索工具调用时同一份真源）
     */
    public KbSearchToolExposure(AgentKbBindAppService binds) {
        this.binds = binds;
    }

    /**
     * 调用名是否就是内置检索工具。runtime 装配只认这个判断，
     * 不必反向依赖工具实现类，工具名仍以 {@link RagSearchTool#toolName()} 为唯一出处。
     *
     * @param toolName tool_item.name
     * @return true 表示知识库检索工具
     */
    public static boolean isKnowledgeTool(String toolName) {
        return toolName != null && RagSearchTool.toolName().equals(toolName.trim());
    }

    /**
     * 本轮该不该交出检索工具，以及交出时给模型看的工具说明。
     * <p>
     * 返回空表示「当前 Agent 没有任何可检索的知识库」：未绑、绑定全关、
     * 绑的库全部不可用（初始化失败 / 停用 / 隔离模式未开放），三者一视同仁。
     *
     * @param agentId 当前 Agent，可空
     * @return 含库清单的工具说明；没有可用库时为空（调用方据此整条不交出）
     */
    public Optional<String> descriptionIfExposed(Long agentId) {
        List<RagKnowledgeBase> usable = usableOf(agentId);
        if (usable.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(compose(usable));
    }

    /**
     * 只读：当前 Agent 可用知识库。供排障与单测断言，不进模型。
     *
     * @param agentId Agent，可空
     * @return 可用库；无则空列表
     */
    public List<RagKnowledgeBase> usableOf(Long agentId) {
        if (agentId == null) {
            return List.of();
        }
        // 仓储理论上不会返回 null；这里仍兜一层，避免装配阶段因一个脏实现把整轮打成失败
        List<RagKnowledgeBase> usable = binds.listUsableForAgent(agentId);
        return usable == null ? List.of() : usable;
    }

    /**
     * 拼工具说明：注解原文在前，其后是按当前绑定生成的库清单与多主题提示。
     * <p>
     * 为什么把清单写进描述而不是 system 的工具目录：目录行只有「名称 + 一句话」且有字符上限，
     * 多库清单一定被裁；工具描述随 {@code tools} 字段整段下发，模型能完整看到每个库的适用范围。
     * <p>
     * 不在这里拆用户问句：只告知「有哪些库、可分主题分别检索」，拆不拆由模型自己决定，
     * 平台侧检索链路（select.kb / 改写 / 召回 / RRF / 重排）完全不变。
     *
     * @param usable 可用库，非空；顺序即绑定序
     * @return 给模型的工具说明
     */
    static String compose(List<RagKnowledgeBase> usable) {
        StringBuilder text = new StringBuilder(RagSearchTool.TOOL_DESCRIPTION);
        text.append("\n\n当前 Agent 绑定了以下知识库（库范围只由绑定决定，调用时无法指定库）：");
        int index = 1;
        for (RagKnowledgeBase kb : usable) {
            text.append('\n').append(index++).append(". 《").append(nameOf(kb)).append("》：")
                    .append(descriptionOf(kb));
        }
        // 一句话里多个主题时，让模型自己按库分别检索；这不改变任何检索协议，只是说明用途
        text.append("\n用户一句话里若包含多个彼此独立的主题，可分别调用本工具，每次只查一个主题。");
        return text.toString();
    }

    /**
     * 库名。名称列非空，但历史数据可能只有空白，兜底成可读占位，避免清单里出现空书名号。
     */
    private static String nameOf(RagKnowledgeBase kb) {
        String name = kb == null || kb.getName() == null ? "" : kb.getName().trim();
        return name.isEmpty() ? UNNAMED : name;
    }

    /**
     * 单库描述：空则固定写「尚无描述」，过长按字截断并留省略号。
     */
    private static String descriptionOf(RagKnowledgeBase kb) {
        String description = kb == null || kb.getDescription() == null ? "" : kb.getDescription().trim();
        if (description.isEmpty()) {
            return NO_DESCRIPTION;
        }
        if (description.length() <= KB_DESCRIPTION_MAX) {
            return description;
        }
        return description.substring(0, KB_DESCRIPTION_MAX) + "…";
    }
}
