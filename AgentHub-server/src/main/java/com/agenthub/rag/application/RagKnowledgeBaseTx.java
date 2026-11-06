package com.agenthub.rag.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.rag.domain.model.RagKnowledgeBase;
import com.agenthub.rag.domain.repository.AgentKbBindRepository;
import com.agenthub.rag.domain.repository.RagChunkRepository;
import com.agenthub.rag.domain.repository.RagDocumentRepository;
import com.agenthub.rag.domain.repository.RagIngestTaskRepository;
import com.agenthub.rag.domain.repository.RagKnowledgeBaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库的「写库阶段」。所属域：rag。
 *
 * <p>存在意义是把向量集合的建 / 删（网络 + 外部进程，可能耗时或挂住）与数据库事务切开：
 * {@link RagKnowledgeBaseAppService} 在**事务外**调用 {@code RagProvider#createStore / dropStore}，
 * 只有落库动作进本类开事务。这样 Milvus 慢时不会长时间占着数据库连接。
 *
 * <p>事务语义：每次公开方法各自一个事务（原先是整段 create/rebuild/remove 一个事务）。
 * 建集失败时状态与原因仍由 {@link #updateInitResult} 一次写入，前端看到的仍是「库已建、初始化失败」。
 *
 * <p>约束：本类不做网络调用、不拼校验文案、不读请求上下文；顺序（先子表再主表）不得调整。
 */
@Service
public class RagKnowledgeBaseTx {

    private final RagKnowledgeBaseRepository kbs;
    private final RagDocumentRepository documentRepository;
    private final RagChunkRepository chunkRepository;
    private final RagIngestTaskRepository ingestTaskRepository;
    private final AgentKbBindRepository agentKbBindRepository;
    private final OperLogWriter operLogWriter;

    /**
     * @param kbs                 知识库主表
     * @param documentRepository  文档表（删库时逻辑删）
     * @param chunkRepository     切片表
     * @param ingestTaskRepository 入库任务表
     * @param agentKbBindRepository Agent 绑定表
     * @param operLogWriter       操作日志（加入当前事务，与业务同生共死）
     */
    public RagKnowledgeBaseTx(RagKnowledgeBaseRepository kbs, RagDocumentRepository documentRepository,
                              RagChunkRepository chunkRepository, RagIngestTaskRepository ingestTaskRepository,
                              AgentKbBindRepository agentKbBindRepository, OperLogWriter operLogWriter) {
        this.kbs = kbs;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.ingestTaskRepository = ingestTaskRepository;
        this.agentKbBindRepository = agentKbBindRepository;
        this.operLogWriter = operLogWriter;
    }

    /**
     * 落库一条 INIT_FAILED 的知识库，供后续建集阶段回写状态。
     *
     * @param kb         已填好业务字段的知识库（id 为空）
     * @param operatorId 操作人
     * @return 自增主键（集合名依赖它生成）
     */
    @Transactional
    public Long insert(RagKnowledgeBase kb, Long operatorId) {
        return kbs.insert(kb, operatorId);
    }

    /**
     * 回写集合初始化结果：成功写集合名 / 能力 / READY，失败写 INIT_FAILED 与原因。
     *
     * @param id             知识库主键
     * @param collectionName 集合名（失败时也保留，便于运维核对）
     * @param capability     存储能力；失败传 null
     * @param status         READY / INIT_FAILED
     * @param reason         失败原因（已截断脱敏）；成功传 null
     * @param operatorId     操作人
     */
    @Transactional
    public void updateInitResult(Long id, String collectionName, String capability,
                                 String status, String reason, Long operatorId) {
        kbs.updateInitResult(id, collectionName, capability, status, reason, operatorId);
    }

    /**
     * 写一条 RAG 操作日志。独立事务：出站已成功/失败的事实不该因为后续异常而丢掉记录。
     *
     * @param action   动作（create / rebuild / remove …）
     * @param targetId 知识库主键
     * @param summary  摘要
     */
    @Transactional
    public void log(String action, String targetId, String summary) {
        operLogWriter.write("rag", action, "rag_knowledge_base", targetId, summary);
    }

    /**
     * 删库的落库部分：级联清子表与绑定，最后逻辑删主表，并写操作日志。
     *
     * <p>顺序不能反：子表按 kb_id 定位，主表删掉后就没法再批量清了。
     *
     * @param kbId       知识库主键
     * @param kb         删除前的快照（取名称拼日志）
     * @param operatorId 操作人
     * @return 关联文档数（仅用于日志摘要）
     */
    @Transactional
    public int deleteCascade(Long kbId, RagKnowledgeBase kb, Long operatorId) {
        int documents = documentRepository.countByKb(kbId);
        chunkRepository.deleteByKb(kbId);
        ingestTaskRepository.deleteByKb(kbId);
        documentRepository.logicalDeleteByKb(kbId);
        agentKbBindRepository.logicalDeleteByKbId(kbId, operatorId);
        kbs.logicalDelete(kbId, operatorId);
        operLogWriter.write("rag", "remove", "rag_knowledge_base", String.valueOf(kbId),
                "删除知识库 " + kb.getName() + (documents > 0 ? "，含 " + documents + " 个文档" : ""));
        return documents;
    }
}
