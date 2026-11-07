package com.agenthub.memory.infra;

import com.agenthub.memory.application.MemoryVectorSupport;
import com.agenthub.memory.domain.model.MemoryVectorReadiness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 记忆向量库启动自检。所属域：memory 的 infra。
 * <p>
 * 监听 {@link ApplicationReadyEvent}：此时 Web 容器已就绪，即使 Milvus 未启动、建集失败或超时，
 * 也只记录日志，绝不阻断启动。失败后由 {@link MemoryVectorSupport} 的重试节流在下次访问时再探测，
 * 这里不注册定时任务，避免对失败批次反复出站。
 */
@Component
public class MemoryVectorBootstrap {

    private static final Logger log = LoggerFactory.getLogger(MemoryVectorBootstrap.class);

    private final MemoryVectorSupport support;

    /**
     * @param support 就绪编排
     */
    public MemoryVectorBootstrap(MemoryVectorSupport support) {
        this.support = support;
    }

    /**
     * 启动自检：ensure 幂等，成功后打印集合/维度/重排可用性；失败只打警告。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void verifyOnStartup() {
        try {
            MemoryVectorReadiness readiness = support.refresh();
            if (readiness.ready()) {
                log.info("[MEMORY] 记忆向量库就绪 collection={} dim={} rerank={}",
                        readiness.collectionName(), readiness.dimension(), readiness.rerankReady());
            } else {
                // 原因里只有配置/实例 id，不含任何密钥
                log.warn("[MEMORY] 记忆向量库未就绪 collection={} dim={} reason={}",
                        readiness.collectionName(), readiness.dimension(), readiness.reason());
            }
        } catch (RuntimeException e) {
            // 兜底：编排层理论上已收敛所有失败，这里再拦一层，保证启动链路不被记忆向量拖垮
            log.warn("[MEMORY] 记忆向量库启动自检异常，已忽略：{}", e.getMessage());
        }
    }
}
