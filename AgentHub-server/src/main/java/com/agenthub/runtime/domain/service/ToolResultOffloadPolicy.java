package com.agenthub.runtime.domain.service;

/**
 * 超长工具结果的落盘策略。所属域：runtime。
 * <p>
 * 为什么要它：工具结果（网页正文、日志、CSV、检索原文）动辄几万字，整段回灌给模型后
 * <b>每一轮</b>都要重新计费，而且在 ReAct 多轮里会反复膨胀。模型真正需要的往往只是
 * 「这轮拿了什么 + 需要时能去读」。
 * <p>
 * 处理方式：超过阈值就落到会话工作区，回灌「摘要 + 虚拟路径」，模型想看细节再调
 * {@code workspaceReadFile} 按行读。阈值可配（{@code agenthub.runtime.trial.tool-result-offload-chars}）。
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
        String name = toolName == null ? "" : toolName.trim().toLowerCase(java.util.Locale.ROOT);
        // 工作区工具返回的就是文件内容：再落一份会让模型在两个路径之间来回读
        return !name.startsWith(WORKSPACE_TOOL_PREFIX);
    }

    /**
     * 回灌给模型的摘要正文。
     * <p>
     * 结构刻意固定为「一句话结论 → 路径 → 怎么读」：模型最容易犯的错是「看不到全文就当没有」，
     * 所以路径与读取指令必须显式出现，且要说明这是可继续读取的，而不是被丢弃。
     * 摘要只取开头一小段：那是标题/状态/表头等最有信息量的部分。
     *
     * @param toolName   工具名，可空
     * @param fullText   原始正文，非空
     * @param virtualPath 虚拟路径，形如 {@code /workspace/tool-results/xxx.txt}
     * @param previewChars 摘要保留的头部字符数
     * @return 回灌正文
     */
    public static String previewOf(String toolName, String fullText, String virtualPath, int previewChars) {
        String text = fullText == null ? "" : fullText;
        int keep = Math.max(0, Math.min(previewChars, text.length()));
        String head = text.substring(0, keep);
        // 头部正好切在多字节字符中间不会出问题（Java char 层面不会产生半个字符），但可能切断一行
        StringBuilder body = new StringBuilder();
        body.append("（工具结果过长，正文共 ").append(text.length())
                .append(" 字符，已保存到工作区文件，不在此重复展开）\n");
        body.append("文件路径：").append(virtualPath).append('\n');
        body.append("需要细节时调用 workspaceReadFile(fileName=\"").append(stripPrefix(virtualPath))
                .append("\", startLine=1, maxLines=100) 分段读取。\n");
        if (keep > 0) {
            body.append("\n【开头摘录】\n").append(head);
            if (keep < text.length()) {
                body.append("\n…（摘录结束，其余内容在文件中）");
            }
        }
        return body.toString();
    }

    /**
     * 去掉虚拟前缀，得到可直接传给 {@code workspaceReadFile} 的相对文件名。
     *
     * @param virtualPath 形如 {@code /workspace/tool-results/xxx.txt}
     * @return 相对文件名
     */
    private static String stripPrefix(String virtualPath) {
        String value = virtualPath == null ? "" : virtualPath;
        return value.startsWith("/workspace/") ? value.substring("/workspace/".length()) : value;
    }
}
