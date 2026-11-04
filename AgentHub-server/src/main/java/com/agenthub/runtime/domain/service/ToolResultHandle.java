package com.agenthub.runtime.domain.service;

/**
 * 一条已经落盘的工具结果句柄。所属域：runtime。
 * <p>
 * 为什么需要句柄：一条几万字的工具结果（网页正文、日志、表格导出）整段回灌给模型，
 * 会在后续每一轮都重复计费，而模型真正需要的往往只是「结论 + 需要时能去读」。
 * 把正文落到会话工作区，只回灌「摘要 + 虚拟路径」，模型想看细节再调 {@code workspaceReadFile} 按段读。
 * <p>
 * 路径必须用虚拟前缀（{@code /workspace/xxx}），禁止出现本机绝对路径——
 * 模型会把它当事实复述给用户，也会被当成可用输入再传回来。
 *
 * @param fileName    工作区内的相对文件名（不含 /workspace/ 前缀）
 * @param virtualPath 给模型看的虚拟路径，形如 {@code /workspace/tool-results/abc.txt}
 * @param totalChars  原文总字符数，供摘要文案说明「省了多少」
 * @param preview     回灌给模型的摘要正文（已含路径与读取指引）
 */
public record ToolResultHandle(String fileName, String virtualPath, int totalChars, String preview) {

    /**
     * @return 正文是否真的落过盘（落盘失败时调用方不应构造句柄）
     */
    public boolean stored() {
        return fileName != null && !fileName.isBlank();
    }
}
