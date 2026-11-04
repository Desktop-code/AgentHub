package com.agenthub.obs.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.obs.api.dto.ObsSessionPageReq;
import com.agenthub.obs.api.dto.ObsSessionResp;
import com.agenthub.obs.api.dto.ObsSpanResp;
import com.agenthub.obs.api.dto.ObsTracePageReq;
import com.agenthub.obs.api.dto.ObsTraceResp;
import com.agenthub.obs.domain.model.ObsChannel;
import com.agenthub.obs.domain.model.ObsSpan;
import com.agenthub.obs.domain.model.ObsTrace;
import com.agenthub.obs.domain.model.ObsTraceQuery;
import com.agenthub.obs.domain.repository.ObsSpanRepository;
import com.agenthub.obs.domain.repository.ObsTraceRepository;
import com.agenthub.obs.domain.service.ObsAttrs;
import com.agenthub.obs.domain.service.SpanDepth;
import com.agenthub.obs.domain.service.SpanEnvelope;
import com.agenthub.obs.domain.spi.WorkflowRunLookup;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.SessionQuery;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 三栏查询。所属域：obs。只读，不删除、不回填历史。
 * 左栏读 agent_run_session，中栏读 obs_trace，右栏按 seq 返回父子步骤。
 * Agent 名称解析失败时留空，由页面回退成 #id。
 */
@Service
public class TraceQueryAppService {

    private final RunSessionRepository sessions;
    private final ObsTraceRepository traces;
    private final ObsSpanRepository spans;
    private final AgentDefRepository agents;
    private final ObsAccess access;
    /** 按工作流定义过滤 trace 时，把 defId 换成最近的 run id 集合（obs 不直接查 workflow 表） */
    private final WorkflowRunLookup workflowRuns;

    /** 单次按工作流过滤最多反查的 run 数；超出部分按时间更早、本刀不保证可筛 */
    private static final int MAX_WORKFLOW_RUNS = 500;

    /**
     * @param sessions     左栏会话
     * @param traces       中栏提问
     * @param spans        右栏步骤
     * @param agents       名称解析
     * @param access       admin 全部，其它仅本人
     * @param workflowRuns 工作流定义 → 最近 run id 反查
     */
    public TraceQueryAppService(RunSessionRepository sessions, ObsTraceRepository traces, ObsSpanRepository spans,
                                AgentDefRepository agents, ObsAccess access, WorkflowRunLookup workflowRuns) {
        this.sessions = sessions;
        this.traces = traces;
        this.spans = spans;
        this.agents = agents;
        this.access = access;
        this.workflowRuns = workflowRuns;
    }

    /**
     * 左栏。时间降序，可按 Agent 和预览 / id 模糊筛选。
     *
     * @param req 分页、q、可选 agentId
     * @return 当前用户能看的会话
     */
    public PageResult<ObsSessionResp> sessions(ObsSessionPageReq req) {
        SessionQuery query = new SessionQuery();
        query.setCurrent(req.getCurrent());
        query.setSize(req.getSize());
        query.setAgentId(req.getAgentId());
        query.setKeyword(blankToNull(req.getQ()));
        query.setUserId(access.ownerFilter());
        PageResult<RunSession> page = sessions.page(query);
        Map<Long, String> names = new HashMap<>();
        List<ObsSessionResp> records = new ArrayList<>();
        for (RunSession session : page.getRecords()) {
            records.add(toSession(session, names));
        }
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), records);
    }

    /**
     * 按工作流查 trace（工作流「运行记录」入口）。
     *
     * <p>为什么走 defId→runId 反查而不是 join：obs 是横切域，不直接依赖 workflow 的仓储；
     * 反查只取 id 列，最终仍用 {@code obs_trace.workflow_run_id} 的索引过滤。
     * <p>workflowId 缺省表示不按定义收窄：此时只取 {@code channel=WORKFLOW} 的根 trace，
     * 供「调用追踪 → 工作流」页签默认分页查询全部运行记录。
     * <p>口径：与对话列表一致，仍按调用方可见范围（非管理员只看自己触发的 run）。
     *
     * @param req 分页、q、workflowId（可选）
     * @return 全部或该工作流最近若干 run 的 trace，按开始时间倒序
     */
    public PageResult<ObsTraceResp> tracesByWorkflow(ObsTracePageReq req) {
        List<String> runIds = null;
        if (req.getWorkflowId() != null) {
            runIds = workflowRuns.recentRunIds(req.getWorkflowId(), MAX_WORKFLOW_RUNS);
            if (runIds.isEmpty()) {
                return PageResult.of(0, req.getCurrent(), req.getSize(), List.of());
            }
        }
        ObsTraceQuery query = new ObsTraceQuery();
        query.setCurrent(req.getCurrent());
        query.setSize(req.getSize());
        query.setWorkflowRunIds(runIds);
        // 未指定定义时按来源收窄到工作流根 trace；指定定义时 run 集合已足够精确
        query.setChannel(req.getWorkflowId() == null ? ObsChannel.WORKFLOW : null);
        query.setUserId(access.ownerFilter());
        query.setKeyword(blankToNull(req.getQ()));
        PageResult<ObsTrace> page = traces.page(query);
        Map<Long, String> names = new HashMap<>();
        List<ObsTraceResp> records = page.getRecords().stream().map(row -> toTrace(row, names)).toList();
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), records);
    }

    /**
     * 中栏。只看这个会话里、调用方有权看到的 trace，按开始时间降序。
     *
     * @param sessionId 会话主键
     * @param req       分页和 title 关键字
     * @return trace 页
     * @throws BizException 会话不存在，或不是当前用户的会话
     */
    public PageResult<ObsTraceResp> traces(Long sessionId, ObsTracePageReq req) {
        Long owner = access.ownerFilter();
        requireSession(sessionId, owner);
        ObsTraceQuery query = new ObsTraceQuery();
        query.setCurrent(req.getCurrent());
        query.setSize(req.getSize());
        query.setSessionId(sessionId);
        query.setUserId(owner);
        query.setKeyword(blankToNull(req.getQ()));
        PageResult<ObsTrace> page = traces.page(query);
        Map<Long, String> names = new HashMap<>();
        List<ObsTraceResp> records = page.getRecords().stream().map(row -> toTrace(row, names)).toList();
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), records);
    }

    /**
     * @param traceId 业务 traceId
     * @return 详情，不含编排预留列
     * @throws BizException 不存在或无权查看
     */
    public ObsTraceResp detail(String traceId) {
        ObsTrace trace = requireTrace(traceId, access.ownerFilter());
        return toTrace(trace, new HashMap<>());
    }

    /**
     * 右栏。按 seq 升序，并带上父子深度。编排节点（TASK）照常返回，供工作流 run 展示节点树。
     *
     * @param traceId 业务 traceId
     * @return 完整步骤，不是分页
     * @throws BizException 不存在或无权查看
     */
    public List<ObsSpanResp> spans(String traceId) {
        ObsTrace trace = requireTrace(traceId, access.ownerFilter());
        List<ObsSpan> rows = new ArrayList<>(spans.listByTraceId(trace.getTraceId()));
        // 父步骤 close 往往早于子步骤落库，展示前把区间扩到包住子孙
        SpanEnvelope.apply(rows);
        Map<Long, Integer> depth = SpanDepth.of(rows);
        Map<Long, String> names = new HashMap<>();
        List<ObsSpanResp> result = new ArrayList<>();
        for (ObsSpan span : rows) {
            result.add(toSpan(span, depth.getOrDefault(span.getId(), 0), names));
        }
        return result;
    }

    private RunSession requireSession(Long sessionId, Long owner) {
        RunSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "会话不存在"));
        if (owner != null && !owner.equals(session.getUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return session;
    }

    private ObsTrace requireTrace(String traceId, Long owner) {
        ObsTrace trace = traces.findByTraceId(traceId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "调用不存在"));
        if (owner != null && !owner.equals(trace.getUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "调用不存在");
        }
        return trace;
    }

    private ObsSessionResp toSession(RunSession session, Map<Long, String> names) {
        ObsSessionResp resp = new ObsSessionResp();
        resp.setId(session.getId());
        resp.setAgentId(session.getAgentId());
        resp.setAgentName(agentName(session.getAgentId(), names));
        resp.setRunMode(session.getRunMode());
        resp.setUserId(session.getUserId());
        resp.setStatus(session.getStatus());
        // 库列已是 start_time；域/左栏 JSON 仍保持 startTime
        resp.setStartTime(session.getStartTime());
        resp.setEndTime(session.getEndTime());
        resp.setSessionTitle(session.getSessionTitle());
        resp.setLastMsgPreview(session.getLastMsgPreview());
        return resp;
    }

    private ObsTraceResp toTrace(ObsTrace trace, Map<Long, String> names) {
        ObsTraceResp resp = new ObsTraceResp();
        resp.setTraceId(trace.getTraceId());
        resp.setSessionId(trace.getSessionId());
        // 编排列：对话场景为空，工作流场景从预留列与 attrs 带回，前端据此展示工作流名与回跳
        resp.setWorkflowRunId(trace.getWorkflowRunId());
        resp.setWorkflowId(ObsAttrs.longField(trace.getAttrsJson(), "workflow_def_id"));
        resp.setWorkflowName(ObsAttrs.textField(trace.getAttrsJson(), "workflow_name"));
        resp.setAgentId(trace.getAgentId());
        resp.setAgentName(agentName(trace.getAgentId(), names));
        resp.setUserId(trace.getUserId());
        resp.setChannel(trace.getChannel() == null ? null : trace.getChannel().name());
        resp.setTitle(trace.getTitle());
        resp.setStatus(trace.getStatus() == null ? null : trace.getStatus().name());
        resp.setStartTime(trace.getStartTime());
        resp.setEndTime(trace.getEndTime());
        resp.setLatencyMs(trace.getLatencyMs());
        resp.setErrorSummary(trace.getErrorSummary());
        resp.setLlmCallCount(trace.getLlmCallCount());
        resp.setToolCallCount(trace.getToolCallCount());
        resp.setTokenPrompt(trace.getTokenPrompt());
        resp.setTokenCompletion(trace.getTokenCompletion());
        resp.setTokenTotal(trace.getTokenTotal());
        resp.setPrimaryModelName(trace.getPrimaryModelName());
        resp.setFinishReason(trace.getFinishReason());
        // trace 级扩展信息：失败时带脱敏 cause，面板第一行就能看到真正原因
        resp.setAttrsJson(trace.getAttrsJson());
        resp.setCause(ObsAttrs.causeOf(trace.getAttrsJson()));
        return resp;
    }

    private ObsSpanResp toSpan(ObsSpan span, int depth, Map<Long, String> names) {
        ObsSpanResp resp = new ObsSpanResp();
        resp.setId(span.getId());
        resp.setParentId(span.getParentId());
        resp.setStepNo(span.getStepNo());
        resp.setSeq(span.getSeq());
        resp.setSpanType(span.getSpanType() == null ? null : span.getSpanType().name());
        resp.setName(span.getName());
        resp.setAgentId(span.getAgentId());
        resp.setAgentName(agentName(span.getAgentId(), names));
        resp.setStatus(span.getStatus() == null ? null : span.getStatus().name());
        resp.setStartTime(span.getStartTime());
        resp.setEndTime(span.getEndTime());
        resp.setLatencyMs(span.getLatencyMs());
        resp.setInputText(span.getInputText());
        resp.setOutputText(span.getOutputText());
        resp.setErrorSummary(span.getErrorSummary());
        resp.setModelInstanceId(span.getModelInstanceId());
        resp.setModelName(span.getModelName());
        resp.setProvider(span.getProvider());
        resp.setTokenPrompt(span.getTokenPrompt());
        resp.setTokenCompletion(span.getTokenCompletion());
        resp.setTokenTotal(span.getTokenTotal());
        resp.setFinishReason(span.getFinishReason());
        // LLM 采样参数：前端 Tooltip，空值由 JSON NON_NULL 省略
        resp.setTemperature(span.getTemperature());
        resp.setMaxTokens(span.getMaxTokens());
        resp.setToolId(span.getToolId());
        resp.setToolName(span.getToolName());
        resp.setToolType(span.getToolType());
        resp.setToolCallId(span.getToolCallId());
        resp.setRetryCount(span.getRetryCount());
        resp.setToolSuccess(span.getToolSuccess());
        resp.setRouteDecision(span.getRouteDecision());
        resp.setChildAgentId(span.getChildAgentId());
        // RETRIEVE 专属列原样透出
        resp.setRetrieveQuery(span.getRetrieveQuery());
        resp.setRetrieveHitCount(span.getRetrieveHitCount());
        resp.setRetrieveTopK(span.getRetrieveTopK());
        resp.setRetrieveKbId(span.getRetrieveKbId());
        resp.setAttrsJson(span.getAttrsJson());
        resp.setBrief(ObsAttrs.briefOf(span.getAttrsJson()));
        resp.setToolsInjected(ObsAttrs.intField(span.getAttrsJson(), "tools_injected"));
        resp.setSkillsInjected(ObsAttrs.intField(span.getAttrsJson(), "skills_injected"));
        resp.setPromptChars(ObsAttrs.intField(span.getAttrsJson(), "prompt_chars"));
        // 分段计量：面板据此展示「钱花在哪一段」，缺失时字段为 null 由前端省略
        resp.setPromptTokensEst(ObsAttrs.intField(span.getAttrsJson(), "prompt_tokens_est"));
        resp.setProtectedChars(ObsAttrs.intField(span.getAttrsJson(), "protected_chars"));
        resp.setSoftChars(ObsAttrs.intField(span.getAttrsJson(), "soft_chars"));
        resp.setMemoryChars(ObsAttrs.intField(span.getAttrsJson(), "memory_chars"));
        resp.setSkillChars(ObsAttrs.intField(span.getAttrsJson(), "skill_chars"));
        resp.setCatalogChars(ObsAttrs.intField(span.getAttrsJson(), "catalog_chars"));
        resp.setSchemaChars(ObsAttrs.intField(span.getAttrsJson(), "schema_chars"));
        resp.setUserChars(ObsAttrs.intField(span.getAttrsJson(), "user_chars"));
        // 用户侧细分与工具句柄：P1 的省 token 来源要能在面板上逐项读到
        resp.setUserSummaryChars(ObsAttrs.intField(span.getAttrsJson(), "user_summary_chars"));
        resp.setUserRecentChars(ObsAttrs.intField(span.getAttrsJson(), "user_recent_chars"));
        resp.setUserShortChars(ObsAttrs.intField(span.getAttrsJson(), "user_short_chars"));
        resp.setUserCurrentChars(ObsAttrs.intField(span.getAttrsJson(), "user_current_chars"));
        resp.setToolResultsOffloaded(ObsAttrs.intField(span.getAttrsJson(), "tool_results_offloaded"));
        resp.setToolResultsOffloadedChars(
                ObsAttrs.intField(span.getAttrsJson(), "tool_results_offloaded_chars"));
        resp.setForcedSkillClipped(ObsAttrs.intField(span.getAttrsJson(), "forced_skill_clipped"));
        resp.setPromptBreakdown(ObsAttrs.textField(span.getAttrsJson(), "prompt_breakdown"));
        resp.setTruncated(ObsAttrs.truncated(span.getAttrsJson()));
        // 仅当新列皆空时带回旧 content，供右栏兼容
        if (isBlank(span.getInputText()) && isBlank(span.getOutputText()) && !isBlank(span.getContent())) {
            resp.setContent(span.getContent());
        }
        resp.setDepth(depth);
        return resp;
    }

    /**
     * 名称解析失败或找不到时返回 null，调用方用 #id 展示。
     */
    private String agentName(Long agentId, Map<Long, String> names) {
        if (agentId == null) {
            return null;
        }
        if (names.containsKey(agentId)) {
            return names.get(agentId);
        }
        String name = null;
        try {
            name = agents.findById(agentId).map(AgentDef::getName).filter(item -> item != null && !item.isBlank()).orElse(null);
        } catch (RuntimeException ex) {
            name = null;
        }
        names.put(agentId, name);
        return name;
    }

    private boolean isBlank(String raw) {
        return raw == null || raw.isBlank();
    }

    private String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }
}
