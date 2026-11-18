package com.agenthub.runtime.infra.agentscope.builtin;

/**
 * 把 Playwright 等底层异常收成可区分的中文原因。所属域：runtime。
 * 不把异常原文回给专家：里面常有本机 Chrome 路径和驱动版本。
 */
public final class BrowserFailures {

    /** 失败发生在哪一段，同一类超时要说成不同的话 */
    public enum Phase {
        /** 进程还没起来 */
        LAUNCH,
        /** 已经有浏览器，正在打开网址 */
        NAVIGATE,
        /** 填表、点击、截图、读文本 */
        ACTION
    }

    private BrowserFailures() {
    }

    /**
     * @param error 底层异常，可空
     * @param phase 失败阶段
     * @return 不含本机路径的中文原因
     */
    public static String explain(Throwable error, Phase phase) {
        Phase where = phase == null ? Phase.NAVIGATE : phase;
        String type = error == null || error.getClass() == null ? "" : error.getClass().getSimpleName();
        String message = error == null || error.getMessage() == null ? "" : error.getMessage();
        boolean timeout = type.contains("Timeout") || message.contains("Timeout");
        if (where == Phase.LAUNCH) {
            return timeout ? "浏览器启动失败：启动超时" : "浏览器启动失败：本机 Chrome 未能启动";
        }
        if (where == Phase.ACTION) {
            return timeout ? "操作失败：找不到元素或操作超时" : "操作失败：页面没有完成这次操作";
        }
        if (timeout) {
            return "打开网页失败：页面加载超时";
        }
        if (message.contains("ERR_NAME_NOT_RESOLVED")) {
            return "打开网页失败：页面打不开（域名无法解析）";
        }
        if (message.contains("ERR_CONNECTION_REFUSED")) {
            return "打开网页失败：页面打不开（连接被拒绝）";
        }
        if (message.contains("ERR_CONNECTION_TIMED_OUT") || message.contains("ERR_TIMED_OUT")) {
            return "打开网页失败：页面打不开（连接超时）";
        }
        if (message.contains("ERR_CERT") || message.contains("SSL")) {
            return "打开网页失败：页面打不开（证书校验失败）";
        }
        if (message.contains("Executable doesn't exist") || message.contains("Failed to launch")) {
            return "浏览器启动失败：本机 Chrome 未能启动";
        }
        return "打开网页失败：页面打不开";
    }
}
