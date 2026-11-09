package com.agenthub.runtime.domain.service;

import com.agenthub.common.api.PageQuery;

/**
 * 试跑会话列表的页大小解析。所属域：runtime。
 *
 * <p>历史会话列表是<b>真分页</b>：页大小只受全站分页上限约束（{@link PageQuery#MAX_SIZE}），
 * 调用方要多少就给多少；{@code history-limit} 只作为「没传 size 时的默认页大小」，
 * <b>不再</b>把任意请求裁回一个固定条数（旧实现固定 20，导致左栏永远只有 20 条）。
 *
 * <p>不在这里做任何「按条数淘汰 / 只留 N 条」的判断：本类只算当前页条数。
 */
public final class HistoryPageSize {

    /** 页大小硬上限，必须与全站分页约定一致（100）；禁止抬高。 */
    public static final long MAX_PAGE_SIZE = PageQuery.MAX_SIZE;

    /** 配置缺失或非法时的兜底默认页大小。 */
    private static final long FALLBACK_DEFAULT = 50L;

    private HistoryPageSize() {
    }

    /**
     * 解析本次查询的页大小。
     *
     * <p>规则：调用方传了正数就用它；没传（0 / 负数）用配置的默认页大小；
     * 两者都会夹到 [1, {@link #MAX_PAGE_SIZE}]。配置非法时退回 50，而不是退回旧的 20。
     *
     * @param requested       前端请求的 size，可为 0 / 负数表示未指定
     * @param defaultPageSize 配置的默认页大小（history-limit 的新语义）
     * @return 落在 [1, 100] 的页大小
     */
    public static long resolve(long requested, int defaultPageSize) {
        long fallback = defaultPageSize > 0 ? Math.min(defaultPageSize, MAX_PAGE_SIZE) : FALLBACK_DEFAULT;
        long size = requested > 0 ? requested : fallback;
        return PageQuery.clampSize(size, MAX_PAGE_SIZE);
    }
}
