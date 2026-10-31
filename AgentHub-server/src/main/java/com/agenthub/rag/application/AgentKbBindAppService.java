package com.agenthub.rag.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.rag.api.dto.KbBindCreateReq;
import com.agenthub.rag.api.dto.KbBindResp;
import com.agenthub.rag.api.dto.KbBindUpdateReq;
import com.agenthub.rag.domain.model.AgentKbBind;
import com.agenthub.rag.domain.model.RagKnowledgeBase;
import com.agenthub.rag.domain.repository.AgentKbBindRepository;
import com.agenthub.rag.domain.repository.RagKnowledgeBaseRepository;
import com.agenthub.rag.domain.service.RagAccessPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 知识库绑定用例。所属域：rag。④ 期的核心。
 * <p>
 * 与 {@code agent_tool_bind} 的分工：
 * <ul>
 *   <li>tool_bind 管「该 Agent 能不能调 RAG 检索工具」；</li>
 *   <li>kb_bind 管「能查哪些库、用什么检索参数」。两者都开才会真正检索。</li>
 * </ul>
 * 校验：绑定前必须确认知识库对当前用户可见且可用（{@link RagAccessPolicy}），
 * 未授权/未初始化的库既不能绑，也不会进运行时。
 */
@Service
public class AgentKbBindAppService {

    private static final Logger log = LoggerFactory.getLogger(AgentKbBindAppService.class);
    /** 单 Agent 可绑知识库上限：过多会让检索扇出拖慢响应 */
    public static final int MAX_BINDS_PER_AGENT = 5;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentDefRepository agents;
    private final RagKnowledgeBaseRepository kbs;
    private final AgentKbBindRepository binds;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;
    private final RagAdminJudge adminJudge;

    /**
     * @param agents          Agent 定义
     * @param kbs             知识库
     * @param binds           绑定仓储
     * @param operatorContext 操作人
     * @param operLogWriter   操作日志
     * @param adminJudge      PRIVATE 库的全量可见判定
     */
    public AgentKbBindAppService(AgentDefRepository agents, RagKnowledgeBaseRepository kbs,
                                 AgentKbBindRepository binds, OperatorContext operatorContext,
                                 OperLogWriter operLogWriter, RagAdminJudge adminJudge) {
        this.agents = agents;
        this.kbs = kbs;
        this.binds = binds;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
        this.adminJudge = adminJudge;
    }

    /**
     * @param agentId Agent 主键
     * @return 该 Agent 的知识库绑定
     */
    public List<KbBindResp> list(Long agentId) {
        requireAgent(agentId);
        return binds.listByAgentId(agentId).stream().map(this::toResp).toList();
    }

    /**
     * 新增绑定。
     *
     * @param agentId Agent 主键
     * @param req     知识库与覆盖项
     * @return 新绑定
     * @throws BizException Agent 或 KB 不存在、KB 不可见/不可用、已绑过、超上限
     */
    @Transactional
    public KbBindResp create(Long agentId, KbBindCreateReq req) {
        requireAgent(agentId);
        RagKnowledgeBase kb = requireVisibleKb(req.getKbId());
        if (binds.existsPair(agentId, kb.getId(), null)) {
            throw new BizException(ErrorCode.DUPLICATE, "该知识库已绑定");
        }
        if (binds.listByAgentId(agentId).size() >= MAX_BINDS_PER_AGENT) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "单个 Agent 最多绑定 " + MAX_BINDS_PER_AGENT + " 个知识库");
        }
        AgentKbBind bind = new AgentKbBind();
        bind.setTenantId(kb.getTenantId());
        bind.setAgentId(agentId);
        bind.setKbId(kb.getId());
        bind.setStatus(RagBindChecks.initialStatus(req.getStatus()));
        bind.setOverridesJson(normalizeOverrides(req.getOverridesJson()));
        Long id = binds.insert(bind, operatorContext.requireUserId());
        operLogWriter.write("rag", "bind-kb", "agent_kb_bind", String.valueOf(id),
                "Agent 绑定知识库 " + kb.getName());
        return toResp(binds.findById(id).orElseThrow());
    }

    /**
     * 改启停与检索覆盖项。
     *
     * @param agentId Agent 主键
     * @param bindId  绑定主键
     * @param req     目标状态与覆盖项
     * @return 更新后的绑定
     */
    @Transactional
    public KbBindResp update(Long agentId, Long bindId, KbBindUpdateReq req) {
        requireAgent(agentId);
        AgentKbBind existing = requireOwned(agentId, bindId);
        // 重新启用时再校验一次可见性：库里可能已被改成 RESTRICTED 或初始化失败
        if (req.getStatus() != null && RagBindChecks.isEnabled(RagBindChecks.exactStatus(req.getStatus()))) {
            requireVisibleKb(existing.getKbId());
        }
        if (req.getStatus() != null) {
            existing.setStatus(RagBindChecks.exactStatus(req.getStatus()));
        }
        if (req.getOverridesJson() != null) {
            existing.setOverridesJson(normalizeOverrides(req.getOverridesJson()));
        }
        binds.update(existing, operatorContext.requireUserId());
        operLogWriter.write("rag", "bind-kb", "agent_kb_bind", String.valueOf(bindId),
                "更新知识库绑定 " + existing.getKbId());
        return toResp(binds.findById(bindId).orElseThrow());
    }

    /**
     * @param agentId Agent 主键
     * @param bindId  绑定主键
     */
    @Transactional
    public void remove(Long agentId, Long bindId) {
        requireAgent(agentId);
        AgentKbBind existing = requireOwned(agentId, bindId);
        binds.logicalDelete(bindId, operatorContext.requireUserId());
        operLogWriter.write("rag", "unbind-kb", "agent_kb_bind", String.valueOf(bindId),
                "移除知识库绑定 " + existing.getKbId());
    }

    /**
     * 知识库被删除时清理全部绑定，避免留下指向不存在库的悬空行。
     *
     * @param kbId       知识库
     * @param operatorId 操作人
     */
    public void removeByKbId(Long kbId, Long operatorId) {
        binds.logicalDeleteByKbId(kbId, operatorId);
    }

    /**
     * 取某 Agent 当前可用的知识库（运行时用）。
     * <p>
     * 过滤顺序：绑定启用 → KB 可访问 → KB 状态与隔离模式可用。
     * 任一不满足都不进运行时，因此「停用绑定」等价于该库对本 Agent 下线。
     *
     * @param agentId Agent 主键
     * @return 可用知识库；无则为空列表
     */
    public List<RagKnowledgeBase> listUsableForAgent(Long agentId) {
        if (agentId == null) {
            return List.of();
        }
        List<RagKnowledgeBase> out = new ArrayList<>();
        for (AgentKbBind bind : binds.listByAgentId(agentId)) {
            if (!RagBindChecks.isEnabled(bind.getStatus())) {
                continue;
            }
            RagKnowledgeBase kb = kbs.findById(bind.getKbId()).orElse(null);
            if (!RagAccessPolicy.usable(kb)) {
                // 绑定开着但库不可用（初始化失败/停用）：跳过而不是报错，其它库仍可检索
                log.debug("[RAG] 绑定指向的知识库当前不可用 agentId={} kbId={}", agentId, bind.getKbId());
                continue;
            }
            out.add(kb);
        }
        return out;
    }

    /**
     * 取某 Agent 的检索覆盖项：知识库 id → overrides_json。
     *
     * @param agentId Agent 主键
     * @return 覆盖项映射；无绑定为空 Map
     */
    public java.util.Map<Long, String> overridesFor(Long agentId) {
        java.util.Map<Long, String> out = new java.util.LinkedHashMap<>();
        if (agentId == null) {
            return out;
        }
        for (AgentKbBind bind : binds.listByAgentId(agentId)) {
            if (RagBindChecks.isEnabled(bind.getStatus()) && bind.getOverridesJson() != null) {
                out.put(bind.getKbId(), bind.getOverridesJson());
            }
        }
        return out;
    }

    /**
     * @param kbId 知识库
     * @return 绑定数，删除知识库前做提示
     */
    public int countByKb(Long kbId) {
        return binds.countByKbId(kbId);
    }

    private void requireAgent(Long agentId) {
        if (agentId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Agent 不能为空");
        }
        AgentDef agent = agents.findById(agentId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "Agent 不存在"));
        // Agent 停用不影响查看/编辑绑定，但绑定列表本身仍要求 Agent 存在
        if (agent.getId() == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Agent 不存在");
        }
    }

    /**
     * 取可见且可用的知识库。不可见与不存在返回同一个提示，避免探库。
     */
    private RagKnowledgeBase requireVisibleKb(Long kbId) {
        RagKnowledgeBase kb = kbId == null ? null : kbs.findById(kbId).orElse(null);
        if (kb == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "知识库不存在");
        }
        Long userId = operatorContext.userIdOrNull();
        if (!RagAccessPolicy.visibleTo(kb, userId, adminJudge.isPrivileged())) {
            // 先判可用性再判可见性：不可用原因更具体（如「初始化失败」），对管理员更有用
            String reason = RagAccessPolicy.rejectReason(kb);
            throw new BizException(ErrorCode.FORBIDDEN, reason == null ? "无权访问该知识库" : reason);
        }
        return kb;
    }

    /**
     * 覆盖项 JSON 校验：必须是对象，长度受限。空按 null（表示全用 KB 默认）。
     */
    private String normalizeOverrides(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.length() > 1000) {
            throw new BizException(ErrorCode.BAD_REQUEST, "检索覆盖项最长 1000");
        }
        if (!text.startsWith("{") || !text.endsWith("}")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "检索覆盖项必须是 JSON 对象");
        }
        try {
            JSON.readTree(text);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "检索覆盖项不是合法 JSON");
        }
        return text;
    }

    private AgentKbBind requireOwned(Long agentId, Long bindId) {
        AgentKbBind bind = binds.findById(bindId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "知识库绑定不存在"));
        if (!agentId.equals(bind.getAgentId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "知识库绑定不存在");
        }
        return bind;
    }

    private KbBindResp toResp(AgentKbBind bind) {
        KbBindResp resp = new KbBindResp();
        resp.setId(bind.getId());
        resp.setAgentId(bind.getAgentId());
        resp.setKbId(bind.getKbId());
        resp.setStatus(bind.getStatus());
        resp.setOverridesJson(bind.getOverridesJson());
        kbs.findById(bind.getKbId()).ifPresent(kb -> {
            resp.setKbName(kb.getName());
            resp.setKbStatus(kb.getStatus());
            resp.setKbCapability(kb.getStoreCapability());
            resp.setKbVisibility(kb.getVisibility());
            resp.setVectorDimension(kb.getVectorDimension());
        });
        return resp;
    }
}

