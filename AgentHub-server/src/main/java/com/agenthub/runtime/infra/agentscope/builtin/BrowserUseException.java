package com.agenthub.runtime.infra.agentscope.builtin;

/**
 * 浏览器工具的可回传失败。所属域：runtime。
 * 文案已经是给专家看的中文，调用方原样返回，不再包一层「浏览器暂时不可用」。
 */
public class BrowserUseException extends RuntimeException {

    /**
     * @param message 给专家的失败原因，不含本机路径
     */
    public BrowserUseException(String message) {
        super(message);
    }
}
