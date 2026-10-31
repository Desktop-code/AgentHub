package com.agenthub.runtime.application;

import com.agenthub.runtime.api.dto.MessageResp;

/**
 * 一轮流式试跑结束后的收口。所属域：runtime。
 * 成功和业务失败都走 done，连接级失败走 fail。
 */
public interface TrialStreamEnd {

    /**
     * @param resp 已落库的本轮结果。status 可能是 FAILED
     */
    void done(MessageResp resp);

    /**
     * 还没形成可落库结果时的失败，例如会话正忙。
     *
     * @param message 给页面的说明，不含堆栈
     */
    void fail(String message);
}
