package com.agenthub.runtime.model.acl.impl;

import com.agenthub.runtime.model.acl.UnifiedToolCall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 流式 tool_calls 碎片累加器。所属域：runtime（模型出站 ACL 的实现细节）。
 * 为什么需要它：上游把每个工具调用拆成多帧，按 <b>index</b> 归组，每帧只带
 * {@code {index, id?, function:{name?, arguments?}}} 的一部分，id 与 name 通常只在首帧出现，
 * arguments 是逐字拼接的字符串片段（可能把一个 JSON 断在半截，甚至切开 unicode 转义）。
 * 因此判断「哪个片段属于哪个调用」必须用 index，不能用 id（后帧没有 id）也不能用下标位置（并发下会乱序）。
 * 本类非线程安全：一个流实例配一个累加器，只在 Reactor 单条订阅链上顺序使用。
 */
final class ToolCallAccumulator {

    /**
     * 按 index 归组的进行中调用。用 LinkedHashMap 保证「首次出现顺序」，
     * 这样 completed 事件的发射顺序与模型意图一致，便于前端按序展示。
     */
    private final Map<Integer, PendingCall> pending = new LinkedHashMap<>();

    /**
     * 累积一帧碎片。
     *
     * @param index      上游给出的归组下标；为 null 时退回「当前最后一个」，兼容少数不返回 index 的中转
     * @param id         调用 id，通常只在首帧出现，后续帧为 null
     * @param name       工具名，通常只在首帧出现，后续帧为 null
     * @param arguments  参数片段，逐字拼接；null 视为本帧无参数
     * @return 本帧对应的快照，供调用方发 tool_call.delta；不会返回 null
     */
    PendingCall accept(Integer index, String id, String name, String arguments) {
        int key = resolveIndex(index);
        PendingCall call = pending.computeIfAbsent(key, ignored -> new PendingCall());
        // id 与 name 只在首帧给，后帧为空字符串时不能覆盖已有值
        if (id != null && !id.isBlank()) {
            call.id = id;
        }
        if (name != null && !name.isBlank()) {
            call.name = name;
        }
        // arguments 必须无脑追加：这是被切成多段的一个 JSON 文本
        if (arguments != null && !arguments.isEmpty()) {
            call.arguments.append(arguments);
        }
        return call;
    }

    /**
     * 解析归组下标。上游没给 index 时退回最后一个已知下标，避免把同一调用的碎片拆成两个。
     *
     * @param index 原始下标
     * @return 归一化后的下标
     */
    private int resolveIndex(Integer index) {
        if (index != null) {
            return index;
        }
        // 没有下标就认为仍属于最近一次出现的调用；空表时用 0 起
        return pending.isEmpty() ? 0 : lastKey();
    }

    /**
     * @return 最后一个插入的下标
     */
    private int lastKey() {
        int last = 0;
        for (Integer key : pending.keySet()) {
            last = key;
        }
        return last;
    }

    /**
     * 生成已完成调用及其归组下标。调用时机由上游 finish_reason=tool_calls 决定，
     * 或由流正常结束时兜底触发（部分中转不发 finish_reason 就直接断）。
     *
     * @return 下标与调用的配对，按首次出现顺序；无调用时返回空列表
     */
    List<Map.Entry<Integer, UnifiedToolCall>> completeEntries() {
        List<Map.Entry<Integer, UnifiedToolCall>> result = new ArrayList<>(pending.size());
        for (Map.Entry<Integer, PendingCall> entry : pending.entrySet()) {
            result.add(Map.entry(entry.getKey(), entry.getValue().toUnified()));
        }
        return result;
    }

    /**
     * @return 当前是否已有工具调用片段
     */
    boolean hasPending() {
        return !pending.isEmpty();
    }

    /**
     * 清空状态。流结束或重订阅时调用，避免串流。
     */
    void reset() {
        pending.clear();
    }

    /**
     * 单个进行中的调用。字段可变、只在累加器内部改。
     */
    static final class PendingCall {

        /** 调用 id；上游未给时留空，由快照阶段兜底生成 */
        private String id;
        /** 工具名 */
        private String name;
        /** 参数原文，多帧拼接结果 */
        private final StringBuilder arguments = new StringBuilder();

        /**
         * @return 调用 id，可能为 null
         */
        String id() {
            return id;
        }

        /**
         * @return 工具名，可能为 null
         */
        String name() {
            return name;
        }

        /**
         * @return 已拼好的参数原文；未收到参数时为空串
         */
        String arguments() {
            return arguments.toString();
        }

        /**
         * 转成对外快照。id 缺失时用「call_<名称>_<序号>」占位，保证回灌 tool 消息时有可用关联键；
         * 参数缺失时给 "{}"，避免下游拿到 null 后拼出非法 JSON 请求。
         *
         * @return 统一工具调用
         */
        UnifiedToolCall toUnified() {
            String resolvedId = (id == null || id.isBlank()) ? "call_" + (name == null ? "unknown" : name) : id;
            String resolvedArgs = arguments.length() == 0 ? "{}" : arguments.toString();
            return UnifiedToolCall.builder()
                    .id(resolvedId)
                    .name(name)
                    .arguments(resolvedArgs)
                    .build();
        }
    }
}
