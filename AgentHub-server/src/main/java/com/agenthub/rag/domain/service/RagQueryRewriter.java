package com.agenthub.rag.domain.service;

/**
 * 查询改写端口。所属域：rag 的 domain。
 * <p>
 * 为什么单列一个端口：改写要读 Agent 的 REWRITE 模型槽与 L3 提示词、还要打一次模型出站，
 * 这些都属于「外部装配」，不该进检索算法。检索服务只认本接口，因此
 * 「一次工具调用最多改一次」「失败回退原问句」都能在检索层用代码固定下来，
 * 单测也可用一个纯函数替身，不必连库连模型。
 * <p>
 * 实现方（{@code RagQueryRewriteService}）必须遵守：
 * <ul>
 *   <li>只解析 {@code REWRITE} 用途槽，<b>禁止</b>降级到 CHAT 模型顶替；</li>
 *   <li>失败不抛异常，用 {@link Result#failed} 说明原因，让检索回退原问句；</li>
 *   <li>出站按 REWRITE 用途计量与埋点，日志不得含密钥。</li>
 * </ul>
 * 线程：阻塞调用，只在工具线程内使用。
 */
public interface RagQueryRewriter {

    /**
     * 调用方身份与计量维度。
     *
     * @param agentId    当前 Agent；非空才会真正改写
     * @param sessionId  本轮运行会话，计量用；可空
     * @param operatorId 平台用户，计量用；可空
     */
    record Caller(Long agentId, Long sessionId, Long operatorId) {

        /**
         * @return 是否具备改写条件（至少要有一个 Agent）
         */
        public boolean present() {
            return agentId != null && agentId > 0;
        }
    }

    /**
     * 一次改写的结果。
     *
     * @param query           生效的检索句：成功时是改写句，否则是原问句
     * @param applied         是否真的用上了改写句
     * @param reason          跳过 / 失败原因（机器可读），成功时为 null
     * @param errorType       异常类型简名，仅失败时非空；供 Trace 区分「超时 / 空结果 / 模型报错」
     * @param modelInstanceId 实际使用的模型实例，跳过时为空
     */
    record Result(String query, boolean applied, String reason, String errorType, Long modelInstanceId) {

        /**
         * 改写成功。
         *
         * @param rewritten       改写句，非空
         * @param modelInstanceId 模型实例
         * @return 结果
         */
        public static Result applied(String rewritten, Long modelInstanceId) {
            return new Result(rewritten, true, null, null, modelInstanceId);
        }

        /**
         * 没有执行改写（门未开 / 模型槽为空 / 提示词不可用）。
         *
         * @param original 原问句
         * @param reason   机器可读原因
         * @return 结果
         */
        public static Result skipped(String original, String reason) {
            return new Result(original, false, reason, null, null);
        }

        /**
         * 执行了但失败，检索回退原问句。
         *
         * @param original        原问句
         * @param reason          机器可读原因
         * @param errorType       异常类型简名，可空
         * @param modelInstanceId 当时的模型实例，可空
         * @return 结果
         */
        public static Result failed(String original, String reason, String errorType, Long modelInstanceId) {
            return new Result(original, false, reason, errorType, modelInstanceId);
        }
    }

    /**
     * 把原问句改写成检索友好的单句。
     * <p>
     * 实现不得抛异常：任何失败都以 {@link Result#failed} 返回，检索侧据此回退原问句继续查。
     *
     * @param caller 调用方身份；为 null 或 agentId 为空时直接按跳过返回
     * @param query  用户原问句
     * @return 结果，永不为 null
     */
    Result rewrite(Caller caller, String query);
}
