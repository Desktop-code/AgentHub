package com.agenthub.runtime.domain.service;

/**
 * 多意图子执行的本轮用户输入隔离。所属域：runtime。
 * 多意图（路由≥2）强制「隔离上下文」：仅 sub_query，不注入父会话历史/其它子内容/父合问。
 * 单意图可继承父侧 prepareUser 前缀（摘要/近轮/短期记忆），只把【当前用户】换成 sub_query。
 * 禁止把父合问整句、其它子的 sub_query 以 append 方式拼进本轮；禁止靠提示词遮串味。
 */
public final class ChildSubQueryInput {

    /** 与 ContextWindowFacade.renderUser 落点一致 */
    static final String CURRENT_MARK = "【当前用户】\n";

    private ChildSubQueryInput() {
    }

    /**
     * 单意图兼容：保留历史前缀，只换本轮。
     *
     * @param modelUser 父侧 prepareUser 结果（含摘要/近轮/当前用户）
     * @param userText  本轮父合问原文
     * @param subQuery  判路由切给该子的子问题；空则退回整句并标记 fallback
     * @return 模型输入 + 观测用本轮子问 + 是否空 sub 回退
     */
    public static Result resolve(String modelUser, String userText, String subQuery) {
        return resolve(modelUser, userText, subQuery, false);
    }

    /**
     * 解析子侧送进 ReAct 的完整用户消息，以及 span 展示用的本轮子问。
     *
     * @param modelUser 父侧 prepareUser 结果（含摘要/近轮/当前用户）；隔离模式下忽略
     * @param userText  本轮父合问原文
     * @param subQuery  判路由切给该子的子问题；空则退回整句并标记 fallback
     * @param isolate   true=硬隔离（仅 sub_query）；false=可继承父历史前缀
     * @return 模型输入 + 观测用本轮子问 + 是否空 sub 回退
     */
    public static Result resolve(String modelUser, String userText, String subQuery, boolean isolate) {
        boolean fallback = subQuery == null || subQuery.isBlank();
        String parent = userText == null ? "" : userText.trim();
        String sub = fallback ? parent : subQuery.trim();
        if (sub.isEmpty() && userText != null) {
            sub = userText;
            fallback = true;
        }
        // 多意图硬隔离：子只吃本条 sub_query，砍掉父历史与合问原文
        if (isolate) {
            return new Result(sub == null ? "" : sub, sub == null ? "" : sub, fallback);
        }
        String model = replaceCurrentTurn(modelUser, parent, userText, sub);
        return new Result(model, sub, fallback);
    }

    /**
     * 把本轮【当前用户】换成 sub；识别不到标记时用尾部父句替换；仍不行则只送 sub（不 append 父句）。
     */
    private static String replaceCurrentTurn(String modelUser, String parent, String userText, String sub) {
        if (sub == null) {
            return "";
        }
        if (modelUser == null || modelUser.isBlank()) {
            return sub;
        }
        if (modelUser.equals(userText) || modelUser.equals(parent) || modelUser.equals(sub)) {
            return sub;
        }
        int markAt = modelUser.lastIndexOf(CURRENT_MARK);
        if (markAt >= 0) {
            // 无论原【当前用户】是合问还是其它，对本子一律换成 sub_query
            return modelUser.substring(0, markAt + CURRENT_MARK.length()) + sub;
        }
        if (!parent.isEmpty() && modelUser.endsWith(parent)) {
            return modelUser.substring(0, modelUser.length() - parent.length()) + sub;
        }
        if (userText != null && !userText.isBlank() && modelUser.endsWith(userText)) {
            return modelUser.substring(0, modelUser.length() - userText.length()) + sub;
        }
        if (!parent.isEmpty()) {
            int last = modelUser.lastIndexOf(parent);
            if (last >= 0) {
                return modelUser.substring(0, last) + sub + modelUser.substring(last + parent.length());
            }
        }
        // 无法安全剥离父句时只送子问，避免 modelUser + sub 双写泄漏
        return sub;
    }

    /**
     * @param modelText       送给子 ReAct 的完整用户侧文本
     * @param displaySubQuery 子 span / 思考展示的本轮用户输入（仅 sub_query）
     * @param fallback        sub_query 为空而退回整句
     */
    public record Result(String modelText, String displaySubQuery, boolean fallback) {
    }
}
