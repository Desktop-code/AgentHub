package com.agenthub.runtime.domain.service;

/**
 * 工作区文件解析失败。所属域：runtime。
 *
 * <p>为什么单独成类：{@code WorkspaceFileTool} 的沙箱校验在 infra，
 * 而下载接口在 api 层，中间隔着 application。用这个异常把「面向人的拒绝原因」
 * 带过边界，调用方再转成业务错误码；异常 message 里<b>禁止</b>出现本机绝对路径。
 */
public class WorkspaceAccessException extends RuntimeException {

    /**
     * @param message 面向用户的拒绝原因，不含绝对路径
     */
    public WorkspaceAccessException(String message) {
        // 这类拒绝是预期分支（越权、文件不存在），关闭堆栈填充只浪费 CPU
        super(message, null, false, false);
    }
}
