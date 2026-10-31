package com.agenthub.runtime.model.acl.impl;

import com.agenthub.runtime.model.acl.UnifiedStreamEvent;
import com.agenthub.runtime.model.acl.UnifiedUsage;

import java.util.List;

/**
 * 厂商流式解码器契约（仅 impl 包内部使用）。所属域：runtime（模型出站 ACL 的实现细节）。
 * 三家 Adapter 各自实现本接口做「chunk → Unified 事件」的状态机，但<b>收尾逻辑完全相同</b>，
 * 由 {@link StreamAssembler} 统一负责，避免每个 Adapter 各写一份 done 兜底而逐渐漂移。
 * 调用方：{@link StreamAssembler#complete}，只在该流的单条 Reactor 订阅链上顺序调用。
 * 线程约束：实现均非线程安全，一个流实例必须新建一个解码器，禁止跨流复用。
 */
interface VendorStreamDecoder {

    /**
     * 解析一帧 data 载荷并产出 0..N 个事件。
     * 一帧可能同时带思考、正文与工具调用分片，所以返回列表而非单值。
     *
     * @param payload 一帧 data 文本（不含 data: 前缀）
     * @return 顺序化的事件列表；解析失败应返回空列表而不是抛异常，避免一帧脏数据打断整条流
     */
    List<UnifiedStreamEvent> onPayload(String payload);

    /**
     * 收尾时把还没发过 completed 的工具调用补发出去。
     *
     * @return completed 事件列表；没有待补的调用时返回空列表
     */
    List<UnifiedStreamEvent> drainCompleted();

    /**
     * @return 当前已知用量，可能为 null（上游未返回 usage）
     */
    UnifiedUsage usage();

    /**
     * 组装正常的 done 事件。实现需保证 finishReason 非 null。
     *
     * @return done 事件
     */
    UnifiedStreamEvent doneEvent();
}
