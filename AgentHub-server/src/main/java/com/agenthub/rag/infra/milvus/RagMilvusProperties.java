package com.agenthub.rag.infra.milvus;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Milvus 连接配置。所属域：rag 的 infra。
 * 只放连接与容量参数；维度、集合名属于单个知识库，不在这里。
 * 账号密码走环境变量，禁止写进仓库（与模型 Key 同等对待）。
 */
@ConfigurationProperties(prefix = "agenthub.rag.milvus")
public class RagMilvusProperties {

    /** Milvus gRPC 地址，例：http://127.0.0.1:19530 */
    private String uri = "http://127.0.0.1:19530";
    /** 鉴权 token（user:password 或 API Key）；空表示不鉴权 */
    private String token = "";
    /** 默认 database；空表示 default */
    private String database = "";
    /** 建连超时，毫秒 */
    private int connectTimeoutMs = 5000;
    /** 加载集合超时，毫秒。注意 Milvus SDK 的 timeout 单位是毫秒，误传秒会立刻超时 */
    private long loadTimeoutMs = 15000;
    /** 建集时是否尝试 BM25（sparse）能力；Lite 不支持会自动降级为纯向量 */
    private boolean bm25Enabled = true;

    /**
     * @return gRPC 地址
     */
    public String getUri() {
        return uri;
    }

    /**
     * @param uri gRPC 地址
     */
    public void setUri(String uri) {
        this.uri = uri;
    }

    /**
     * @return 鉴权 token
     */
    public String getToken() {
        return token;
    }

    /**
     * @param token 鉴权 token
     */
    public void setToken(String token) {
        this.token = token;
    }

    /**
     * @return 默认 database
     */
    public String getDatabase() {
        return database;
    }

    /**
     * @param database 默认 database
     */
    public void setDatabase(String database) {
        this.database = database;
    }

    /**
     * @return 建连超时毫秒
     */
    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    /**
     * @param connectTimeoutMs 建连超时毫秒
     */
    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    /**
     * @return 加载集合超时毫秒
     */
    public long getLoadTimeoutMs() {
        return loadTimeoutMs;
    }

    /**
     * @param loadTimeoutMs 加载集合超时毫秒
     */
    public void setLoadTimeoutMs(long loadTimeoutMs) {
        this.loadTimeoutMs = loadTimeoutMs;
    }

    /**
     * @return 是否尝试建 BM25 集合
     */
    public boolean isBm25Enabled() {
        return bm25Enabled;
    }

    /**
     * @param bm25Enabled 是否尝试建 BM25 集合
     */
    public void setBm25Enabled(boolean bm25Enabled) {
        this.bm25Enabled = bm25Enabled;
    }
}
