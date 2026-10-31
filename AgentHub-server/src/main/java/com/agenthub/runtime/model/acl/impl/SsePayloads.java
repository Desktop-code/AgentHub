package com.agenthub.runtime.model.acl.impl;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Flux;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * SSE 字节流 → data 载荷的切分工具。所属域：runtime（模型出站 ACL 的实现细节）。
 * 为什么按「行」切而不是按「空行事件」切：绝大多数厂商每条 data 就是一行 JSON，
 * 且按行切天然避开多字节字符被 buffer 边界截断的问题——只有遇到 \n（ASCII）才解码，
 * 此时该行之前的 UTF-8 序列必然完整。
 * 线程约束：无状态静态方法；每次调用新建自己的跨包残行缓冲，可被任意多路流并发使用。
 */
final class SsePayloads {

    /** 上游流结束标记。收到即代表数据结束，之后不再产出业务事件 */
    static final String DONE_MARKER = "[DONE]";

    private SsePayloads() {
    }

    /**
     * 判断一个载荷是否只是结束标记。
     *
     * @param payload data 载荷，可空
     * @return true 表示结束标记，不产生业务事件
     */
    static boolean isDoneMarker(String payload) {
        return payload != null && DONE_MARKER.equals(payload.trim());
    }

    /**
     * 把原始字节流切成一条条 SSE data 载荷。
     * 只保留 data: 行；注释行（冒号开头）与 event:/id:/retry: 行一律丢弃，
     * 因为统一事件结构里不需要帧级 event 名（Anthropic 的帧类型在 data 的 type 字段里）。
     *
     * @param source 原始字节流
     * @return 每个元素是一条完整的 data 载荷（已去前缀）
     */
    static Flux<String> split(Flux<DataBuffer> source) {
        return Flux.defer(() -> {
            // 跨 buffer 的残行必须留住，否则一个 JSON 会被切成两条无效帧
            ByteArrayOutputStream carry = new ByteArrayOutputStream(512);
            return source
                    .concatMap(buffer -> {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        // 池化 DataBuffer 必须显式释放，否则 Netty 直接内存泄漏
                        DataBufferUtils.release(buffer);
                        carry.write(bytes, 0, bytes.length);
                        return Flux.fromIterable(drainLines(carry));
                    })
                    // 上游没补换行就断开时，carry 里可能还剩最后半帧
                    .concatWith(Flux.defer(() -> {
                        String rest = extractData(new String(carry.toByteArray(), StandardCharsets.UTF_8));
                        carry.reset();
                        return rest.isEmpty() ? Flux.empty() : Flux.just(rest);
                    }));
        });
    }

    /**
     * 从累积字节里取出所有完整行，转成 data 载荷。
     * 只处理到最后一个 \n 为止，剩余半行留在 carry 里等下一个 buffer。
     *
     * @param carry 累积缓冲，方法内会消费掉已完整部分
     * @return 本轮可交付的载荷，按到达顺序
     */
    private static List<String> drainLines(ByteArrayOutputStream carry) {
        byte[] all = carry.toByteArray();
        int lastNewline = -1;
        for (int i = all.length - 1; i >= 0; i--) {
            if (all[i] == '\n') {
                lastNewline = i;
                break;
            }
        }
        if (lastNewline < 0) {
            // 尚无完整行，全部留在 carry
            return List.of();
        }
        String complete = new String(all, 0, lastNewline + 1, StandardCharsets.UTF_8);
        // 保留 lastNewline 之后的残行
        carry.reset();
        carry.write(all, lastNewline + 1, all.length - lastNewline - 1);
        List<String> payloads = new ArrayList<>();
        for (String line : complete.split("\n")) {
            String payload = extractData(line);
            if (!payload.isEmpty()) {
                payloads.add(payload);
            }
        }
        return payloads;
    }

    /**
     * 从一行文本里取出 data 内容。忽略注释行（: 开头）与 event:/id:/retry: 行。
     *
     * @param line 单行
     * @return data 载荷；非 data 行返回空串
     */
    private static String extractData(String line) {
        String trimmed = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
        if (trimmed.isEmpty() || trimmed.startsWith(":")) {
            return "";
        }
        if (trimmed.startsWith("data:")) {
            return trimmed.substring("data:".length()).trim();
        }
        return "";
    }
}
