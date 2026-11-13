package com.agenthub.runtime.domain.service;

import java.util.Collection;
import java.util.Locale;

/**
 * 超长工具结果的落盘策略。所属域：runtime。
 * <p>
 * 为什么要它：工具结果（网页正文、日志、CSV、检索原文）动辄几万字，整段回灌给模型后
 * <b>每一轮</b>都要重新计费，而且在 ReAct 多轮里会反复膨胀。模型真正需要的往往只是
 * 「这轮拿了什么 + 需要时能去读」。
 * <p>
 * 处理方式：超过阈值就落到会话工作区，回灌「摘要 + 虚拟路径」，模型想看细节再按行读。
 * 阈值可配（{@code agenthub.runtime.trial.tool-result-offload-chars}）。
 * <p>
 * <b>关键约束（本刀收口）</b>：回灌摘要里只能点名<b>当前专家真的绑定了的</b>读文件工具。
 * 早期实现无条件写「请调用 workspaceReadFile」，而检索类专家往往只绑了
 * {@code searchKnowledgeBase}：模型照做→工具不存在→再次检索→连续失败→自我熔断。
 * 因此「没有绑定读文件工具时干脆不落盘」，让超长正文留在结果里，由回灌截断
 * （{@link com.agenthub.runtime.infra.agentscope.ToolResultClipper}）给出可读的头部正文，
 * 既不点名不存在的工具，也不逼模型反复重搜。
 * <p>
 * <b>不落盘的例外</b>（这些落盘只会制造噪音与风险）：
 * <ul>
 *   <li>本来就在操作工作区的工具（{@code workspace*}）：它返回的是文件内容本身，
 *       再落一份只会形成「文件套文件」；</li>
 *   <li>失败结果：错误正文通常很短，且模型需要看到完整原因才能改参数；</li>
 *   <li>会话标识缺失：没有沙箱目录就无从落盘，此时退回原行为（交给字符截断兜底）。</li>
 * </ul>
 * <p>
 * 线程安全：无状态；每次调用都新建文件名，不共享可变状态。
 */
public final class ToolResultOffloadPolicy {

    /** 不参与落盘的工具名前缀：它们本来就在操作工作区 */
    private static final String WORKSPACE_TOOL_PREFIX = "workspace";

    /** 平台内置读文件工具名（{@code WorkspaceFileTool} 的 @Tool 名） */
    public static final String READ_FILE_TOOL = "workspaceReadFile";

    private ToolResultOffloadPolicy() {
    }

    /**
     * 是否应把这条结果落盘。
     *
     * @param toolName 工具调用名，可空
     * @param text     结果正文，可空
     * @param success  引擎报告的成功与否
     * @param offloadMaxChars 落盘阈值；≤0 表示关闭落盘
     * @return true 表示应落盘
     */
    public static boolean shouldOffload(String toolName, String text, boolean success, int offloadMaxChars) {
        if (offloadMaxChars <= 0 || !success) {
            return false;
        }
        if (text == null || text.length() <= offloadMaxChars) {
            return false;
        }
        String name = toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
        // 工作区工具返回的就是文件内容：再落一份会让模型在两个路径之间来回读
        return !name.startsWith(WORKSPACE_TOOL_PREFIX);
    }

    /**
     * 从本轮已装配的工具名里挑出「能用来回读落盘正文」的读文件工具。
     * <p>
     * 只认已注册的名字：没绑就是不返回 null，调用方据此<b>放弃落盘</b>，
     * 而不是在提示里点名一个当前专家根本没有的工具。
     * 优先取平台标准名 {@code workspaceReadFile}；其次接受同义的
     * {@code workspace*Read*}（未来换名也不会重新出现「点名不存在工具」的坑）。
     *
     * @param toolNames 本轮 Toolkit 里实际注册的工具名；可空
     * @return 可安全点名的读文件工具名；没有则 null
     */
    public static String readToolIn(Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return null;
        }
        String fallback = null;
        for (String raw : toolNames) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String name = raw.trim();
            String lower = name.toLowerCase(Locale.ROOT);
            if (READ_FILE_TOOL.toLowerCase(Locale.ROOT).equals(lower)) {
                // 标准名直接命中：统一回标准写法，避免历史大小写差异把工具名拼错
                return READ_FILE_TOOL;
            }
            if (fallback == null && lower.startsWith(WORKSPACE_TOOL_PREFIX) && lower.contains("read")) {
                fallback = name;
            }
        }
        return fallback;
    }

    /**
     * 回灌给模型的摘要正文。
     * <p>
     * 结构刻意固定为「一句话结论 → 路径 → 怎么读」：模型最容易犯的错是「看不到全文就当没有」，
     * 所以路径与读取指令必须显式出现，且要说明这是可继续读取的，而不是被丢弃。
     * 摘要只取开头一小段：那是标题/状态/表头等最有信息量的部分。
     * <p>
     * 读文件工具名必须由 {@link #readToolIn(Collection)} 从真实绑定里取：
     * 传空时只写路径与「已保存」事实，<b>绝不</b>出现任何工具名。
     *
     * @param toolName   工具名，可空
     * @param fullText   原始正文，非空
     * @param virtualPath 虚拟路径，形如 {@code /workspace/tool-results/xxx.txt}
     * @param previewChars 摘要保留的头部字符数
     * @param readToolName 当前专家已绑定的读文件工具名；没有则空（此时不写读取指令）
     * @return 回灌正文
     */
    public static String previewOf(String toolName, String fullText, String virtualPath, int previewChars,
                                   String readToolName) {
        String text = fullText == null ? "" : fullText;
        int keep = Math.max(0, Math.min(previewChars, text.length()));
        String head = text.substring(0, keep);
        // 头部正好切在多字节字符中间不会出问题（Java char 层面不会产生半个字符），但可能切断一行
        StringBuilder body = new StringBuilder();
        body.append("（工具结果过长，正文共 ").append(text.length())
                .append(" 字符，已保存到工作区文件，不在此重复展开）\n");
        body.append("文件路径：").append(virtualPath).append('\n');
        if (readToolName != null && !readToolName.isBlank()) {
            body.append("需要细节时调用 ").append(readToolName.trim())
                    .append("(fileName=\"").append(stripPrefix(virtualPath))
                    .append("\", startLine=1, maxLines=100) 分段读取。\n");
        } else {
            // 没有可点名的读文件工具：只陈述事实，不给任何工具指令（防止模型去调不存在的工具）
            body.append("（本轮未提供读文件工具，请直接依据下方摘录作答，不要为读取全文重复调用检索工具）\n");
        }
        if (keep > 0) {
            body.append("\n【开头摘录】\n").append(head);
            if (keep < text.length()) {
                body.append("\n…（摘录结束，其余内容在文件中）");
            }
        }
        return body.toString();
    }

    /**
     * 去掉虚拟前缀，得到可直接传给读文件工具的相对文件名。
     *
     * @param virtualPath 形如 {@code /workspace/tool-results/xxx.txt}
     * @return 相对文件名
     */
    private static String stripPrefix(String virtualPath) {
        String value = virtualPath == null ? "" : virtualPath;
        return value.startsWith("/workspace/") ? value.substring("/workspace/".length()) : value;
    }
}
