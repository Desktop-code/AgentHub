package com.agenthub.rag.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.rag.api.dto.RagKbCreateReq;
import com.agenthub.rag.api.dto.RagKbResp;
import com.agenthub.rag.api.dto.RagKbUpdateReq;
import com.agenthub.rag.domain.model.RagKnowledgeBase;
import com.agenthub.rag.domain.repository.RagKnowledgeBaseRepository;
import com.agenthub.rag.domain.service.RagKbNamePolicy;
import com.agenthub.spi.rag.RagProvider;
import com.agenthub.spi.rag.RagStoreException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库建库与初始化。所属域：rag 的 application。
 * <p>
 * 覆盖重点（对应验收口径）：
 * <ol>
 *   <li>建集成功 → 状态 READY、写回集合名与能力；</li>
 *   <li>BM25 不可用 → 仍 READY，但能力是 DENSE_ONLY（本机 Milvus Lite 的真实路径）；</li>
 *   <li>建集失败 → 不抛异常给前端，落 INIT_FAILED 且带 errorMsg，可重建恢复；</li>
 *   <li>LOGICAL / RESTRICTED / 第三方 store 在一期被拒绝，且不写库；</li>
 *   <li>删库时集合删除失败 → 记录保留，不产生孤儿集合。</li>
 * </ol>
 * 这里用 mock 替代真实 Milvus：Milvus 的集成验证由人工在本机跑建/删库，单测不依赖外部进程。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagKnowledgeBaseAppServiceTest {

    @Mock
    private RagKnowledgeBaseRepository kbs;
    @Mock
    private RagKbNamePolicy names;
    @Mock
    private ModelInstanceRepository instances;
    @Mock
    private RagProvider provider;
    @Mock
    private OperatorContext operatorContext;
    @Mock
    private OperLogWriter operLogWriter;
    @Mock
    private com.agenthub.rag.domain.repository.RagDocumentRepository documentRepository;
    @Mock
    private com.agenthub.rag.domain.repository.RagChunkRepository chunkRepository;
    @Mock
    private com.agenthub.rag.domain.repository.RagIngestTaskRepository ingestTaskRepository;
    @Mock
    private com.agenthub.rag.domain.repository.RagDocumentParseRepository documentParseRepository;
    @Mock
    private com.agenthub.rag.domain.repository.AgentKbBindRepository agentKbBindRepository;

    /** 模拟库里那一行；由 stubCreateBasics 的 insert / updateInitResult 共同维护 */
    private RagKnowledgeBase stored;

    /**
     * 建集成功：维度取自模型 extraJson，状态 READY，集合名按 kb_id 生成。
     */
    @Test
    void createMarksReadyWhenStoreCreated() {
        stubCreateBasics();
        // Provider 模拟成功：回填集合名与能力
        org.mockito.Mockito.doAnswer(inv -> {
            RagKnowledgeBase kb = inv.getArgument(0);
            kb.setCollectionName("agenthub_kb_" + kb.getId());
            kb.setStoreCapability("DENSE_BM25");
            return null;
        }).when(provider).createStore(any(RagKnowledgeBase.class));

        RagKbResp resp = service().create(createReq());

        assertEquals("READY", resp.getStatus());
        assertNull(resp.getErrorMsg());
        assertEquals(768, resp.getVectorDimension());
        assertEquals("DENSE_BM25", resp.getStoreCapability());
        // 落库的初始化结果：集合名 + 能力 + READY + errorMsg 清空
        verify(kbs).updateInitResult(eq(10L), eq("agenthub_kb_10"), eq("DENSE_BM25"),
                eq("READY"), isNull(), eq(9L));
    }

    /**
     * 本机 Milvus Lite 不支持 sparse：Provider 降级为 DENSE_ONLY，建库仍算成功。
     * 这条保证了「BM25 建不出来」不会把知识库卡成不可用。
     */
    @Test
    void createSucceedsWithDenseOnlyWhenBm25Unsupported() {
        stubCreateBasics();
        org.mockito.Mockito.doAnswer(inv -> {
            RagKnowledgeBase kb = inv.getArgument(0);
            kb.setCollectionName("agenthub_kb_" + kb.getId());
            kb.setStoreCapability("DENSE_ONLY");
            return null;
        }).when(provider).createStore(any(RagKnowledgeBase.class));

        RagKbResp resp = service().create(createReq());

        assertEquals("READY", resp.getStatus());
        assertEquals("DENSE_ONLY", resp.getStoreCapability());
    }

    /**
     * 建集失败：接口仍返回 200 语义（不抛），库里落 INIT_FAILED 与原因，便于列表里直接重建。
     */
    @Test
    void createRecordsInitFailedWhenStoreThrows() {
        stubCreateBasics();
        doThrow(new RagStoreException("无法连接向量库（http://127.0.0.1:19530）"))
                .when(provider).createStore(any(RagKnowledgeBase.class));

        RagKbResp resp = service().create(createReq());

        assertEquals("INIT_FAILED", resp.getStatus());
        assertTrue(resp.getErrorMsg().contains("无法连接向量库"));
        verify(kbs).updateInitResult(eq(10L), anyString(), isNull(), eq("INIT_FAILED"),
                anyString(), eq(9L));
    }

    /**
     * LOGICAL 一期不做：必须在写库之前拒绝，否则会建出一个没有 kb_id 过滤的共集合库。
     */
    @Test
    void rejectsLogicalIsolationBeforeWriting() {
        RagKbCreateReq req = createReq();
        req.setIsolationMode("LOGICAL");

        assertThrows(BizException.class, () -> service().create(req));
        verify(kbs, never()).insert(any(RagKnowledgeBase.class), anyLong());
        verify(provider, never()).createStore(any(RagKnowledgeBase.class));
    }

    /**
     * RESTRICTED 依赖授权表，一期拒绝，避免「看着私密其实全开」。
     */
    @Test
    void rejectsRestrictedVisibility() {
        RagKbCreateReq req = createReq();
        req.setVisibility("RESTRICTED");

        assertThrows(BizException.class, () -> service().create(req));
        verify(kbs, never()).insert(any(RagKnowledgeBase.class), anyLong());
    }

    /**
     * 第三方 Dataset 未实装：选择即拒绝，不能建出一条没有存储的知识库。
     */
    @Test
    void rejectsUnimplementedStoreType() {
        RagKbCreateReq req = createReq();
        req.setStoreType("DIFY_API");

        assertThrows(BizException.class, () -> service().create(req));
        verify(provider, never()).createStore(any(RagKnowledgeBase.class));
    }

    /**
     * 模型没写维度、请求也没给：拒绝创建，不允许猜维度建出写不进向量的集合。
     */
    @Test
    void rejectsWhenDimensionUnknown() {
        stubCreateBasics();
        ModelInstance noDim = embeddingInstance("text-embedding-v3", "{\"temperature\":0.7}");
        when(instances.findById(100L)).thenReturn(Optional.of(noDim));
        RagKbCreateReq req = createReq();
        req.setVectorDimension(null);

        BizException ex = assertThrows(BizException.class, () -> service().create(req));
        assertTrue(ex.getMessage().contains("无法确定向量维度"));
        verify(kbs, never()).insert(any(RagKnowledgeBase.class), anyLong());
    }

    /**
     * 维度兜底：模型 extraJson 有 dimension 时优先，覆盖请求里乱填的值。
     */
    @Test
    void prefersModelDimensionOverRequest() {
        stubCreateBasics();
        RagKbCreateReq req = createReq();
        req.setVectorDimension(1024);

        RagKbResp resp = service().create(req);

        assertEquals(768, resp.getVectorDimension());
    }

    /**
     * 重建：Provider 成功则回到 READY，并清空上次的失败原因。
     */
    @Test
    void rebuildResetsToReady() {
        RagKnowledgeBase existing = readyKb("INIT_FAILED", "上次连接失败");
        existing.setCollectionName("agenthub_kb_10");
        when(kbs.findById(10L)).thenReturn(Optional.of(existing));
        when(operatorContext.requireUserId()).thenReturn(9L);
        org.mockito.Mockito.doAnswer(inv -> {
            RagKnowledgeBase kb = inv.getArgument(0);
            kb.setStoreCapability("DENSE_ONLY");
            return null;
        }).when(provider).createStore(any(RagKnowledgeBase.class));

        service().rebuild(10L);

        verify(kbs).updateInitResult(eq(10L), eq("agenthub_kb_10"), eq("DENSE_ONLY"),
                eq("READY"), isNull(), eq(9L));
    }

    /**
     * 删库：集合删除失败时记录必须保留，否则集合再也无法定位。
     */
    @Test
    void keepsRecordWhenDropStoreFails() {
        RagKnowledgeBase existing = readyKb("READY", null);
        existing.setCollectionName("agenthub_kb_10");
        when(kbs.findById(10L)).thenReturn(Optional.of(existing));
        when(operatorContext.requireUserId()).thenReturn(9L);
        doThrow(new RagStoreException("Milvus 未启动")).when(provider).dropStore(any(RagKnowledgeBase.class));

        assertThrows(BizException.class, () -> service().remove(10L));
        verify(kbs, never()).logicalDelete(anyLong(), anyLong());
    }

    /**
     * 删库：集合不存在（Provider 幂等返回）时正常逻辑删记录。
     */
    @Test
    void deletesRecordWhenDropStoreSucceeds() {
        RagKnowledgeBase existing = readyKb("READY", null);
        existing.setCollectionName("agenthub_kb_10");
        when(kbs.findById(10L)).thenReturn(Optional.of(existing));
        when(operatorContext.requireUserId()).thenReturn(9L);

        service().remove(10L);

        verify(provider).dropStore(any(RagKnowledgeBase.class));
        verify(kbs).logicalDelete(10L, 9L);
    }

    /**
     * 删库必须级联清理子表与绑定：否则会留下指向不存在库的悬空行，
     * 检索时靠 kb_id 查不到数据但排障时极其误导。
     */
    @Test
    void removeCascadesChildrenAndBindings() {
        RagKnowledgeBase existing = readyKb("READY", null);
        existing.setCollectionName("agenthub_kb_10");
        when(kbs.findById(10L)).thenReturn(Optional.of(existing));
        when(operatorContext.requireUserId()).thenReturn(9L);
        when(documentRepository.countByKb(10L)).thenReturn(3);

        service().remove(10L);

        verify(chunkRepository).deleteByKb(10L);
        verify(ingestTaskRepository).deleteByKb(10L);
        verify(documentRepository).logicalDeleteByKb(10L);
        verify(agentKbBindRepository).logicalDeleteByKbId(10L, 9L);
        // 主表必须最后删：先删主表就再也按 kb_id 定位不到子表了
        verify(kbs).logicalDelete(10L, 9L);
    }

    /**
     * 更新：不接受把 Rerank 换成本地不存在的实例；0 表示清空。
     */
    @Test
    void updateClearsRerankWhenZero() {
        RagKnowledgeBase existing = readyKb("READY", null);
        existing.setRerankModelId(55L);
        when(kbs.findById(10L)).thenReturn(Optional.of(existing));
        when(operatorContext.requireUserId()).thenReturn(9L);
        RagKbUpdateReq req = new RagKbUpdateReq();
        req.setName("政策库");
        req.setDescription("售后政策与 FAQ");
        req.setVisibility("PRIVATE");
        req.setRerankModelId(0L);

        RagKbResp resp = service().update(10L, req);

        assertNull(resp.getRerankModelId());
        ArgumentCaptor<RagKnowledgeBase> captor = ArgumentCaptor.forClass(RagKnowledgeBase.class);
        verify(kbs).updateProfile(captor.capture(), eq(9L));
        assertNull(captor.getValue().getRerankModelId());
    }

    private RagKnowledgeBaseAppService service() {
        // 写库阶段已拆成独立协作者（事务边界）；用例只做 wiring，断言仍盯着 repository / operLog 的调用
        RagKnowledgeBaseTx tx = new RagKnowledgeBaseTx(kbs, documentRepository, chunkRepository,
                ingestTaskRepository, documentParseRepository, agentKbBindRepository, operLogWriter);
        // RagModelLookup 用真实实现：维度解析是本次逻辑的一部分，mock 掉会漏测
        return new RagKnowledgeBaseAppService(kbs, names,
                new RagModelLookup(instances, new ObjectMapper()), instances,
                provider, operatorContext, operLogWriter, tx);
    }

    /**
     * 建库公共桩：名称不冲突、操作人固定、insert 返回 10。
     * <p>
     * 这里用一个 {@code stored} 模拟「库里那一行」：insert 写入、updateInitResult 改状态、findById 回读，
     * 这样 create/rebuild 之后读到的才是更新后的状态（直接 mock 返回固定对象会把状态改动静默吞掉）。
     */
    private void stubCreateBasics() {
        when(operatorContext.requireUserId()).thenReturn(9L);
        ModelInstance embedding = embeddingInstance("text-embedding-v3", "{\"dimension\":768}");
        when(instances.findById(100L)).thenReturn(Optional.of(embedding));
        when(kbs.insert(any(RagKnowledgeBase.class), eq(9L))).thenAnswer(inv -> {
            stored = inv.getArgument(0);
            stored.setId(10L);
            return 10L;
        });
        when(kbs.findById(10L)).thenAnswer(inv -> Optional.of(copyOf(stored)));
        // 初始化结果回写：同步到 stored，模拟 update ... set status/error_msg 落库
        org.mockito.Mockito.doAnswer(inv -> {
            stored.setCollectionName(inv.getArgument(1));
            stored.setStoreCapability(inv.getArgument(2));
            stored.setStatus(inv.getArgument(3));
            stored.setErrorMsg(inv.getArgument(4));
            return null;
        }).when(kbs).updateInitResult(eq(10L), any(), any(), any(), any(), eq(9L));
    }

    private RagKbCreateReq createReq() {
        RagKbCreateReq req = new RagKbCreateReq();
        req.setName("售后政策库");
        req.setDescription("售后 FAQ");
        req.setEmbeddingModelId(100L);
        req.setIsolationMode("PHYSICAL");
        req.setVisibility("PRIVATE");
        return req;
    }

    private ModelInstance embeddingInstance(String displayName, String extraJson) {
        ModelInstance instance = new ModelInstance();
        instance.setId(100L);
        instance.setDisplayName(displayName);
        instance.setCapability("EMBEDDING");
        instance.setStatus(1);
        instance.setExtraJson(extraJson);
        return instance;
    }

    private RagKnowledgeBase readyKb(String status, String errorMsg) {
        RagKnowledgeBase kb = new RagKnowledgeBase();
        kb.setId(10L);
        kb.setName("售后政策库");
        kb.setStoreType("LOCAL_MILVUS");
        kb.setEmbeddingModelId(100L);
        kb.setVectorDimension(768);
        kb.setIsolationMode("PHYSICAL");
        kb.setVisibility("PRIVATE");
        kb.setStatus(status);
        kb.setErrorMsg(errorMsg);
        return kb;
    }

    /**
     * 简单拷贝：模拟「回读数据库」的语义，避免测试里共享同一可变实例造成误判。
     */
    private static RagKnowledgeBase copyOf(RagKnowledgeBase src) {
        RagKnowledgeBase copy = new RagKnowledgeBase();
        copy.setId(src.getId());
        copy.setName(src.getName());
        copy.setDescription(src.getDescription());
        copy.setStoreType(src.getStoreType());
        copy.setCollectionName(src.getCollectionName());
        copy.setStoreCapability(src.getStoreCapability());
        copy.setEmbeddingModelId(src.getEmbeddingModelId());
        copy.setVectorDimension(src.getVectorDimension());
        copy.setRerankModelId(src.getRerankModelId());
        copy.setIsolationMode(src.getIsolationMode());
        copy.setVisibility(src.getVisibility());
        copy.setRetrievalConfig(src.getRetrievalConfig());
        copy.setIngestConfig(src.getIngestConfig());
        copy.setStatus(src.getStatus());
        copy.setErrorMsg(src.getErrorMsg());
        return copy;
    }
}
