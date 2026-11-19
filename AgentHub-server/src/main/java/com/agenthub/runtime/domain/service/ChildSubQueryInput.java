package com.agenthub.runtime.domain.service;

/**
 * 多意图子执行的本轮用户输入隔离。所属域：runtime。
 * 多意图（路由≥2）强制「隔离上下文」：仅 sub_query，不注入父会话历史/其它子内容/父合问。
 * 单意图可继承父侧 prepareUser 前缀（摘要/近轮/短期记忆），只把【当前用户】换成 sub_query。
 * 禁止把父合问整句、其它子的 sub_query 以 append 方式拼进本轮；禁止靠提示词遮串味。
 *
 * <p><b>素材与问句分开</b>：入口预处理会把识图 / 抽字 / 转写正文拼进本轮原文，
 * 判路由产出的 {@code sub_query} 往往是一句概述。此处只替换「问句」区间，
 * 附件派生的事实（{@link #resolve(String, String, String, boolean, String)} 的 material）原样跟在问句之后，
 * 否则「先追问办理事项」会把票面一起扔掉。有素材时不参与隔离判断——素材是本轮事实，不是历史。
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
        return resolve(modelUser, userText, subQuery, false, null);
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
        return resolve(modelUser, userText, subQuery, isolate, null);
    }

    /**
     * 带「附件素材」的解析：问句仍按 sub_query 改写，素材单独保留在问句之后。
     *
     * <p>为什么素材不参与 isolate：多意图隔离的目的是「别让这个子看到别的子的问题与父历史」，
     * 而素材是用户这一轮上传的同一批事实，每个被分到的子都该看到；砍掉它只会让专家
     * 反过来向用户再要一遍已经抽出来的字段。
     *
     * <p>幂等：{@code sub_query} 本身已经含素材（点名方案 / 关键字 / 固定模板等
     * {@code subQuery == userText} 的路径）时不再追加第二份。
     *
     * @param modelUser 父侧 prepareUser 结果（含摘要/近轮/当前用户）；隔离模式下忽略
     * @param userText  本轮父合问原文
     * @param subQuery  判路由切给该子的子问题；空则退回整句并标记 fallback
     * @param isolate   true=硬隔离历史（素材仍保留）；false=可继承父历史前缀
     * @param material  附件派生正文（识图 / 转写 / 抽字），可空；空时与三参重载逐字一致
     * @return 模型输入 + 观测用本轮子问 + 是否空 sub 回退
     */
    public static Result resolve(String modelUser, String userText, String subQuery, boolean isolate,
                                 String material) {
        boolean fallback = subQuery == null || subQuery.isBlank();
        String parent = userText == null ? "" : userText.trim();
        String sub = fallback ? parent : subQuery.trim();
        if (sub.isEmpty() && userText != null) {
            sub = userText;
            fallback = true;
        }
        String body = sub == null ? "" : sub;
        // 多意图硬隔离：子只吃本条 sub_query，砍掉父历史与合问原文；素材是本轮事实，照旧带上
        if (isolate) {
            return new Result(withMaterial(body, material), body, fallback);
        }
        String model = withMaterial(replaceCurrentTurn(modelUser, parent, userText, body), material);
        return new Result(model, body, fallback);
    }

    /**
     * 把本轮要匹配的文本给技能触发 / 记忆召回用：子问在前，素材在后。
     *
     * <p>为什么不直接用 {@code displaySubQuery}：判路由常把「这张发票什么时候到期」写成
     * 「确认用户诉求」，光靠它匹配不到 Skill 触发词，也召不回与票面相关的长期记忆。
     * 这里只拼文本给匹配用，不改任何抽取与落库口径。
     *
     * @param subQuery 子问；可空
     * @param material 附件素材；可空
     * @return 匹配用文本；两者都空时为空串
     */
    public static String matchText(String subQuery, String material) {
        // 不 trim 子问：串行交接传进来的整段文本要原样保留，拼接只发生在有素材时
        return withMaterial(subQuery == null ? "" : subQuery, material);
    }

    /**
     * 在问句之后接上素材；素材为空、或问句里已经含着这段素材时原样返回。
     *
     * @param question 问句（可能为空）
     * @param material 素材段
     * @return 拼接结果
     */
    private static String withMaterial(String question, String material) {
        String body = question == null ? "" : question;
        String facts = material == null ? "" : material.trim();
        if (facts.isEmpty()) {
            return body;
        }
        // subQuery == userText（点名 / 关键字 / 固定模板 / 手动换线）时素材本来就在问句里：
        // 再拼一份会让模型收到两遍票面，还额外花 token
        if (!body.isEmpty() && body.contains(facts)) {
            return body;
        }
        return body.isEmpty() ? facts : body + "\n\n" + facts;
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
