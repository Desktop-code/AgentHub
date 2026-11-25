package com.agenthub.rag.domain.service;

import io.milvus.v2.common.DataType;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import com.agenthub.rag.domain.model.RagKbStatus;
import com.agenthub.rag.domain.model.RagStoreCapability;
import com.agenthub.rag.domain.model.RagStoreType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集合命名与字段模板。所属域：rag 的 domain。
 * <p>
 * 这两处是「建库契约」：名字不合规会导致 Milvus 建集失败或误删其它集合；
 * 字段模板改动会影响所有新库，必须有测试把 §10.3 的字段名钉住。
 */
class RagCollectionSchemaTest {

    /**
     * 命名规则：前缀 + kb_id，且能被识别为平台所有。
     */
    @Test
    void buildsOwnedCollectionNameFromKbId() {
        String name = RagCollectionName.of(42L);
        assertEquals("agenthub_kb_42", name);
        assertTrue(RagCollectionName.ownedByPlatform(name));
    }

    /**
     * 非法 kb_id 直接拒绝，避免拼出 agenthub_kb_null 这类集合名。
     */
    @Test
    void rejectsIllegalKbId() {
        assertThrows(IllegalArgumentException.class, () -> RagCollectionName.of(null));
        assertThrows(IllegalArgumentException.class, () -> RagCollectionName.of(0L));
        assertThrows(IllegalArgumentException.class, () -> RagCollectionName.of(-5L));
    }

    /**
     * 非本规则的集合名一律不认，删库时据此拒绝，防止误删同实例其它业务集合。
     */
    @Test
    void refusesForeignCollectionNames() {
        assertFalse(RagCollectionName.ownedByPlatform(null));
        assertFalse(RagCollectionName.ownedByPlatform(""));
        assertFalse(RagCollectionName.ownedByPlatform("knowledge_chunks_v2"));
        assertFalse(RagCollectionName.ownedByPlatform("agenthub_kb_"));
        assertFalse(RagCollectionName.ownedByPlatform("agenthub_kb_1_doc"));
        assertFalse(RagCollectionName.ownedByPlatform("other_kb_1"));
    }

    /**
     * 标量字段必须与 docs §10.3 完全一致：多一个少一个都会让 ②③ 期写入/检索对不上。
     */
    @Test
    void scalarFieldsMatchFieldSheet() {
        List<String> names = RagCollectionSchema.scalarFields().stream()
                .map(CreateCollectionReq.FieldSchema::getName)
                .toList();
        assertEquals(List.of(
                "kb_id", "doc_id", "chunk_id", "tenant_id", "dept_id", "version",
                "content_hash", "deleted", "effective_from", "expire_time",
                "create_by", "owner_user_id", "title", "source"), names);
    }

    /**
     * deleted 用 Int8：Milvus 标量过滤对整数稳定，且与 MySQL tinyint 语义一致。
     */
    @Test
    void deletedIsInt8() {
        CreateCollectionReq.FieldSchema deleted = RagCollectionSchema.scalarFields().stream()
                .filter(field -> "deleted".equals(field.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals(DataType.Int8, deleted.getDataType());
    }

    /**
     * 主键必须是非自增 Int64：值由平台按 MySQL rag_chunk.id 回填，两边才能对齐。
     */
    @Test
    void primaryKeyIsManualInt64() {
        CreateCollectionReq.FieldSchema id = RagCollectionSchema.idField();
        assertEquals("id", id.getName());
        assertEquals(DataType.Int64, id.getDataType());
        assertEquals(Boolean.TRUE, id.getIsPrimaryKey());
        assertEquals(Boolean.FALSE, id.getAutoID());
    }

    /**
     * 向量字段按传入维度建，且必须是 FloatVector。
     */
    @Test
    void vectorFieldUsesGivenDimension() {
        CreateCollectionReq.FieldSchema vector = RagCollectionSchema.vectorField(1024);
        assertEquals("embedding", vector.getName());
        assertEquals(DataType.FloatVector, vector.getDataType());
        assertEquals(1024, vector.getDimension());
    }

    /**
     * BM25 文本字段必须开 chinese analyzer；纯向量降级版本不能开（Lite 不支持全文）。
     */
    @Test
    void analyzedContentFieldEnablesChineseAnalyzer() {
        CreateCollectionReq.FieldSchema analyzed = RagCollectionSchema.analyzedContentField();
        assertEquals("content", analyzed.getName());
        assertEquals(Boolean.TRUE, analyzed.getEnableAnalyzer());
        assertNotNull(analyzed.getAnalyzerParams());
        assertEquals("chinese", analyzed.getAnalyzerParams().get("type"));
        assertEquals(RagCollectionSchema.CONTENT_MAX_LENGTH, analyzed.getMaxLength());

        CreateCollectionReq.FieldSchema plain = RagCollectionSchema.plainContentField();
        assertTrue(plain.getEnableAnalyzer() == null || Boolean.FALSE.equals(plain.getEnableAnalyzer()));
    }

    /**
     * sparse 字段类型必须是 SparseFloatVector，否则 BM25 索引建不出来。
     */
    @Test
    void sparseFieldUsesSparseFloatVector() {
        CreateCollectionReq.FieldSchema sparse = RagCollectionSchema.sparseField();
        assertEquals("sparse", sparse.getName());
        assertEquals(DataType.SparseFloatVector, sparse.getDataType());
    }

    /**
     * 枚举解析：未知值按最保守语义处理，避免脏数据被当成「可用」。
     */
    @Test
    void enumsFallBackToConservativeValues() {
        assertEquals(RagStoreCapability.DENSE_ONLY, RagStoreCapability.parse(null));
        assertEquals(RagStoreCapability.DENSE_ONLY, RagStoreCapability.parse("weird"));
        assertEquals(RagStoreCapability.DENSE_BM25, RagStoreCapability.parse("dense_bm25"));
        assertEquals(RagKbStatus.INIT_FAILED, RagKbStatus.parse("nope"));
        assertEquals(RagKbStatus.READY, RagKbStatus.parse("ready"));
    }

    /**
     * 存储类型留空按 LOCAL_MILVUS：前端不传时不该失败，且一期只有这一种实现。
     */
    @Test
    void blankStoreTypeDefaultsToLocalMilvus() {
        assertEquals(RagStoreType.LOCAL_MILVUS, RagStoreType.parse(null));
        assertEquals(RagStoreType.LOCAL_MILVUS, RagStoreType.parse("  "));
        assertThrows(IllegalArgumentException.class, () -> RagStoreType.parse("UNKNOWN"));
    }
}
