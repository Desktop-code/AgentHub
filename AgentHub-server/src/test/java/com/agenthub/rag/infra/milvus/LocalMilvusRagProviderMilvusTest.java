package com.agenthub.rag.infra.milvus;

import com.agenthub.rag.domain.model.RagKnowledgeBase;
import com.agenthub.rag.domain.service.RagCollectionName;
import com.agenthub.spi.rag.RagStoreException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地 Milvus 建集 / 删集的真实集成测试。所属域：rag 的 infra。
 * <p>
 * 这是本期唯一能验证「Milvus 出现对应 Collection 且 schema 含约定标量」的手段，因此走真实连接而非 mock。
 * <p>
 * 可用性约定：Milvus 未启动时整类 <b>skip</b>（Assumptions），不把环境缺失当成失败；
 * 启动方式由人工执行 {@code D:\Environment\milvus\start.py}，测试自身不启动、不修改其环境。
 * <p>
 * 集合名固定为 {@code agenthub_kb_990001}，每个用例结束都清理，避免留下测试数据。
 * 本机为 Milvus Lite v2.2.16：不支持 SparseFloatVector，会走 BM25 降级分支（DENSE_ONLY）。
 */
class LocalMilvusRagProviderMilvusTest {

    /** 测试用集合 id；本地固定值，便于人工在 Milvus 里核对与清理 */
    private static final long TEST_KB_ID = 990001L;

    private LocalMilvusRagProvider provider;
    private RagKnowledgeBase kb;

    /**
     * 连不上 Milvus 就跳过：本机 Lite 需要人工先启动。
     */
    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(milvusListening(), "本机 Milvus 未监听 19530，跳过集成测试");

        RagMilvusProperties properties = new RagMilvusProperties();
        properties.setUri("http://127.0.0.1:19530");
        properties.setConnectTimeoutMs(3000);
        properties.setLoadTimeoutMs(20000);
        properties.setBm25Enabled(true);
        provider = new LocalMilvusRagProvider(new MilvusClientHolder(properties), properties);

        kb = new RagKnowledgeBase();
        kb.setId(990001L);
        kb.setName("集成测试库");
        kb.setStoreType("LOCAL_MILVUS");
        kb.setVectorDimension(8);
        kb.setIsolationMode("PHYSICAL");
    }

    /**
     * 无论用例成功与否都清理，避免在用户库实例上留下测试集合。
     */
    @AfterEach
    void tearDown() {
        if (provider == null) {
            return;
        }
        try {
            provider.dropStore(kb);
        } catch (RuntimeException ignored) {
            // 清理失败不影响用例结论；人工可核对 agenthub_kb_990001 是否存在
        }
    }

    /**
     * 建集：维度按 KB 写入，能力是 DENSE_BM25 或 DENSE_ONLY（Lite 降级），且建集后不残留测试集合。
     */
    @Test
    void createsCollectionWithPlatformFields() {
        provider.createStore(kb);

        assertEquals("agenthub_kb_990001", kb.getCollectionName());
        assertNotNull(kb.getStoreCapability());
        // Lite v2.2.16 不支持 SparseFloatVector → 这里应为 DENSE_ONLY；standalone 会得到 DENSE_BM25
        assertTrue("DENSE_BM25".equals(kb.getStoreCapability())
                || "DENSE_ONLY".equals(kb.getStoreCapability()));

        // 幂等：重复 createStore 不报错，且能力保持一致
        String firstCapability = kb.getStoreCapability();
        provider.createStore(kb);
        assertEquals(firstCapability, kb.getStoreCapability());
        assertEquals(firstCapability, provider.probeCapability(kb));

        provider.dropStore(kb);
        // 删除幂等：再删一次不报错
        provider.dropStore(kb);
    }

    /**
     * 反向验证 schema 内容：直接读 describeCollection，确认 §10.3 约定的标量字段一个不少。
     * <p>
     * 这条比「建集成功」更有价值——建集成功只说明没报错，字段是否按模板落下来必须看真实 schema。
     */
    @Test
    void collectionSchemaContainsAllPlatformScalarFields() {
        provider.createStore(kb);

        // 用同一个 holder 再拿一次客户端读 describe，避免测试类自己持有连接
        RagMilvusProperties properties = new RagMilvusProperties();
        properties.setUri("http://127.0.0.1:19530");
        MilvusClientHolder holder = new MilvusClientHolder(properties);
        io.milvus.v2.service.collection.response.DescribeCollectionResp resp = holder.client()
                .describeCollection(io.milvus.v2.service.collection.request.DescribeCollectionReq.builder()
                        .collectionName(kb.getCollectionName())
                        .build());

        java.util.Set<String> actual = resp.getCollectionSchema().getFieldSchemaList().stream()
                .map(io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema::getName)
                .collect(java.util.stream.Collectors.toSet());

        // 主键 + 向量 + 文本 + 全部平台标量字段
        assertTrue(actual.containsAll(java.util.List.of(
                "id", "embedding", "content",
                "kb_id", "doc_id", "chunk_id", "tenant_id", "dept_id", "version",
                "content_hash", "deleted", "effective_from", "expire_time",
                "create_by", "owner_user_id", "title", "source")),
                "缺少平台字段，实际为 " + actual);

        // 维度必须与 KB 一致：建错维度会让 ② 期写入永远失败
        io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema embedding =
                resp.getCollectionSchema().getFieldSchemaList().stream()
                        .filter(f -> "embedding".equals(f.getName()))
                        .findFirst().orElseThrow();
        assertEquals(kb.getVectorDimension(), embedding.getDimension());
    }

    /**
     * 维度非法时在建集前就失败，不产生残缺集合。
     */
    @Test
    void rejectsMissingDimension() {
        kb.setVectorDimension(null);
        assertThrows(RagStoreException.class, () -> provider.createStore(kb));
    }

    /**
     * 非平台命名的集合拒绝删除，防止误删同实例其它业务集合。
     */
    @Test
    void refusesToDropForeignCollection() {
        kb.setCollectionName("knowledge_chunks_v2");
        assertThrows(RagStoreException.class, () -> provider.dropStore(kb));
        // 清掉，避免 tearDown 再次触发同一断言
        kb.setCollectionName(null);
    }

    /**
     * 命名规则自检：测试用的固定 id 必须被识别为平台集合，否则删库时会被拒。
     */
    @Test
    void testCollectionNameIsOwnedByPlatform() {
        assertTrue(RagCollectionName.ownedByPlatform(RagCollectionName.of(990001L)));
        assertFalse(RagCollectionName.ownedByPlatform("agenthub_kb_"));
        assertEquals("agenthub_kb_990001", RagCollectionName.of(990001L));
    }

    /**
     * 探测端口是否可连；用纯 TCP 而非 SDK，避免探测本身抛异常干扰 Assumption。
     */
    private static boolean milvusListening() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 19530), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
