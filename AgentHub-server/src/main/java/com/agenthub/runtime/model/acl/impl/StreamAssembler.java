package com.agenthub.runtime.model.acl.impl;

import com.agenthub.runtime.model.acl.UnifiedStreamEvent;
import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式收尾装配器：把「上游字节流 → 厂商解码器 → Unified 事件流」串起来，并强制 done 兜底。
 * 所属域：runtime（模型出站 ACL 的实现细节）。
 * 为什么抽出来：done 兜底是硬规则（正常/截断/错误/超时/取消五种路径都必须有且仅有一个 done），
 * 三家 Adapter 各自实现必然漂移；这里只做一次，Adapter 只需提供「请求怎么发 + chunk 怎么读」。
 * 收尾覆盖：
 * 1. 上游正常结束（读到 [DONE] 或自然 EOF）→ 补 completed + done；
 * 2. 上游缺结束标识直接断流 → 同上，结束原因按已观察到的状态推导；
 * 3. 上游报错（非 2xx / 解析崩溃）→ 先补 completed，再 error，再 done；
 * 4. 读空闲超时 → error(TIMEOUT) + done(TIMEOUT)；
 * 5. 客户端取消 → 下游已无接收方，无法投递事件，只能标记并让 Reactor 释放上游连接。
 * 线程约束：无状态；所有可变状态都在 {@link #complete} 的单次订阅闭包内新建。
 */
final class StreamAssembler {

    private StreamAssembler() {
    }

    /**
     * 组装带 done 兜底的统一事件流。
     *
     * @param body         上游字节流（已带上请求、超时与非 2xx 映射）
     * @param decoderFactory 每次订阅新建解码器；用于隔离多路复用 Adapter 实例上的流内状态
     * @return 事件流；正常与异常路径末尾都有且仅有一个 done
     */
    static Flux<UnifiedStreamEvent> assemble(Flux<DataBuffer> body, DecoderFactory decoderFactory) {
        return Flux.defer(() -> {
            VendorStreamDecoder decoder = decoderFactory.create();
            // done 只允许发一次：正常收尾与异常收尾竞争，用 CAS 决定归属
            AtomicBoolean doneSent = new AtomicBoolean(false);

            Flux<UnifiedStreamEvent> events = SsePayloads.split(body)
                    // concatMap 保证顺序：tool_call 碎片必须严格按到达顺序拼接，flatMap 会打乱
                    .concatMap(payload -> {
                        if (SsePayloads.isDoneMarker(payload)) {
                            return Flux.empty();
                        }
                        return Flux.fromIterable(decoder.onPayload(payload));
                    });

            // 正常收尾：补发未发出的 completed，再发 done
            Flux<UnifiedStreamEvent> closing = Flux.defer(() -> {
                List<UnifiedStreamEvent> tail = new ArrayList<>(decoder.drainCompleted());
                if (doneSent.compareAndSet(false, true)) {
                    tail.add(decoder.doneEvent());
                }
                return Flux.fromIterable(tail);
            });

            return events
                    .concatWith(closing)
                    // 异常收尾：先发 error 摘要再发 done，保证前端能结束 Loading
                    .onErrorResume(error -> Flux.defer(() ->
                            Flux.fromIterable(UpstreamFailures.errorEvents(error, decoder, doneSent))))
                    // 客户端取消：下游已断开，无法再投递事件，只能标记并让 Reactor 释放上游连接
                    .doOnCancel(() -> doneSent.set(true))
                    // 最终兜底：任何路径都没发过 done 时补一个，杜绝「流结束了但前端还在 Loading」
                    .concatWith(Flux.defer(() -> {
                        if (doneSent.get()) {
                            return Flux.empty();
                        }
                        doneSent.set(true);
                        return Flux.just(decoder.doneEvent());
                    }));
        });
    }

    /**
     * 解码器工厂。用函数式接口而不是直接传实例，强制「每条订阅新建一个」这一约束。
     * 所属域：runtime（模型出站 ACL 的实现细节）。
     */
    @FunctionalInterface
    interface DecoderFactory {

        /**
         * @return 新建的解码器；不得返回共享实例
         */
        VendorStreamDecoder create();
    }
}
