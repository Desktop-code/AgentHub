package com.agenthub.rag.infra.milvus;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Milvus 客户端持有者。所属域：rag 的 infra。
 * <p>
 * 双检锁懒连接：控制台未进知识库页时不建立 gRPC 长连接，也不因 Milvus 未启动导致应用启动失败。
 * 连接失败只清空引用并抛 {@link IllegalStateException}，下次调用会重试——这样用户先起 Milvus 再点「重建」即可恢复。
 */
@Component
public class MilvusClientHolder {

    private static final Logger log = LoggerFactory.getLogger(MilvusClientHolder.class);

    private final RagMilvusProperties properties;

    /** 已建立的客户端；null 表示尚未连接或上次连接失败 */
    private volatile MilvusClientV2 client;

    /** 保护 client 的建连过程，避免并发建多条 gRPC 通道 */
    private final Object lock = new Object();

    /**
     * @param properties 连接参数
     */
    public MilvusClientHolder(RagMilvusProperties properties) {
        this.properties = properties;
    }

    /**
     * 取可用客户端，必要时建连。
     *
     * @return Milvus 客户端
     * @throws IllegalStateException 连接失败（含地址非法、Milvus 未启动）
     */
    public MilvusClientV2 client() {
        MilvusClientV2 current = client;
        if (current != null) {
            return current;
        }
        synchronized (lock) {
            if (client != null) {
                return client;
            }
            String uri = properties.getUri() == null ? "" : properties.getUri().trim();
            if (!StringUtils.hasText(uri)) {
                throw new IllegalStateException("未配置 agenthub.rag.milvus.uri");
            }
            ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder()
                    .uri(uri)
                    .connectTimeoutMs(Math.max(properties.getConnectTimeoutMs(), 1000));
            // 只在配置了 token / database 时才设置，避免向无鉴权的本地实例发送空凭证
            if (StringUtils.hasText(properties.getToken())) {
                builder.token(properties.getToken().trim());
            }
            if (StringUtils.hasText(properties.getDatabase())) {
                builder.dbName(properties.getDatabase().trim());
            }
            try {
                MilvusClientV2 created = new MilvusClientV2(builder.build());
                client = created;
                log.info("[RAG][milvus] 已连接 {}", uri);
                return created;
            } catch (Exception e) {
                // 不缓存失败，保证 Milvus 后启动时下一次调用能重连
                log.warn("[RAG][milvus] 连接失败 {}: {}", uri, e.getMessage(), e);
                // 该消息会写进 rag_knowledge_base.error_msg，面向上管理员，可带地址便于排障；
                // 但不带任何「本机脚本路径 / 启动命令」，对话侧由工具边界统一换成用户话术
                throw new IllegalStateException("知识库存储连接失败（" + uri + "）", e);
            }
        }
    }

    /**
     * 丢弃当前连接。连接层面的致命错误（通道断开）后由调用方触发重连。
     */
    public void reset() {
        synchronized (lock) {
            MilvusClientV2 current = client;
            client = null;
            if (current != null) {
                try {
                    current.close();
                } catch (Exception e) {
                    log.debug("[RAG][milvus] 关闭旧连接失败: {}", e.getMessage());
                }
            }
        }
    }
}
