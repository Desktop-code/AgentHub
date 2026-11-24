package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentQuery;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.MaDef;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.MaDefRepository;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.runtime.api.dto.AgentOptionResp;
import com.agenthub.runtime.api.dto.EventResp;
import com.agenthub.runtime.api.dto.SessionPageReq;
import com.agenthub.runtime.api.dto.SessionResp;
import com.agenthub.runtime.domain.model.EntryChannel;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.RunStatus;
import com.agenthub.runtime.domain.model.SessionQuery;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import com.agenthub.runtime.domain.service.HistoryPageSize;
import com.agenthub.runtime.domain.service.SessionCancelFace;
import com.agenthub.runtime.domain.service.TurnStillOpen;
import com.agenthub.runtime.infra.agentscope.TrialProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 运行会话的只读查询：下拉、能力、分页、详情、事件回放与实时快照。所属域：runtime。
 *
 * <p>本类只读库与内存镜像，不写会话状态、不触发取消 / 清空 / 删除；
 * 所有入口都先过 {@link TrialAccess}，管理员也不放行别人的运行会话。
 */
@Service
public class TrialSessionQueryService {

    private final RunSessionRepository sessions;
    private final RunEventRepository events;
    private final AgentDefRepository agents;
    private final TrialAccess access;
    private final OperatorContext operatorContext;
    private final TrialProperties properties;
    private final TrialLiveBuffer live;
    private final TrialBindingLoader loader;
    /** 方案读源：列表回显 maCode / maName */
    private final MaDefRepository mas;
    /**
     * 刷新回放补全：落库卡片只存骨架，读侧按 runId 从 workflow_node_run 现算节点产出。
     * 与直播共用同一套取文口径，前端协议形状不变。
     */
    private final WorkflowCardRebuilder cardRebuilder;

    /**
     * @param sessions        会话
     * @param events          事件
     * @param agents          只读定义，用来校验启停和回填名称
     * @param access          仅会话所属用户
     * @param operatorContext 当前用户
     * @param properties      历史条数上限（未传 size 时的默认页大小）
     * @param live            进行中一轮的内存快照，供刷新后的只读轮询
     * @param loader          只读模型槽解析，用于判定是否配了 VISION / ASR / TTS
     * @param mas             只读方案，列表回显 maCode / maName
     * @param cardRebuilder   回放补全：按 runId 从节点运行态现算工作流卡片的节点产出
     */
    public TrialSessionQueryService(RunSessionRepository sessions, RunEventRepository events,
                                    AgentDefRepository agents, TrialAccess access,
                                    OperatorContext operatorContext, TrialProperties properties,
                                    TrialLiveBuffer live, TrialBindingLoader loader, MaDefRepository mas,
                                    WorkflowCardRebuilder cardRebuilder) {
        this.sessions = sessions;
        this.events = events;
        this.agents = agents;
        this.access = access;
        this.operatorContext = operatorContext;
        this.properties = properties;
        this.live = live;
        this.loader = loader;
        this.mas = mas;
        this.cardRebuilder = cardRebuilder;
    }

    /**
     * 运行页下拉。只返回启用中的 Agent，避免再要求 agent:list。
     *
     * <p>顺带带出「是否已绑定可用 VISION / ASR / TTS 模型」：前端据此置灰上传与「说话」按钮、
     * 并决定朗读走模型还是浏览器。逐个 Agent 判定的成本是一次绑定表查询 + 少量实例查询，
     * 100 条上限下可以接受；比起让用户传完图或说完话再被拒，这点开销值得。
     *
     * @return 最多 100 条
     */
    public List<AgentOptionResp> enabledAgents() {
        AgentQuery query = new AgentQuery();
        query.setCurrent(1);
        query.setSize(100);
        query.setStatus(AgentStatus.ENABLED);
        return agents.page(query).getRecords().stream().map(agent -> {
            AgentOptionResp resp = new AgentOptionResp();
            resp.setId(agent.getId());
            resp.setName(agent.getName());
            resp.setCode(agent.getCode());
            resp.setRunMode(agent.getRunMode());
            resp.setVisionEnabled(loader.findVisionModel(agent.getId()).isPresent());
            // 语音两用途各自独立：只绑识别也能听写，只绑合成也能朗读
            resp.setAsrEnabled(loader.findSpeechModel(agent.getId(), ModelCapability.ASR).isPresent());
            resp.setTtsEnabled(loader.findSpeechModel(agent.getId(), ModelCapability.TTS).isPresent());
            return resp;
        }).toList();
    }

    /**
     * 单个 Agent 的多模态 / 语音能力。抽屉直接按 agentId 打开时用它，
     * 不必为了几个布尔值把整个下拉列表拉一遍。
     *
     * @param agentId Agent
     * @return 能力视图
     * @throws BizException Agent 不存在
     */
    public AgentOptionResp capability(Long agentId) {
        AgentDef agent = agents.findById(agentId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "Agent 不存在"));
        AgentOptionResp resp = new AgentOptionResp();
        resp.setId(agent.getId());
        resp.setName(agent.getName());
        resp.setCode(agent.getCode());
        resp.setRunMode(agent.getRunMode());
        resp.setVisionEnabled(loader.findVisionModel(agent.getId()).isPresent());
        resp.setAsrEnabled(loader.findSpeechModel(agent.getId(), ModelCapability.ASR).isPresent());
        resp.setTtsEnabled(loader.findSpeechModel(agent.getId(), ModelCapability.TTS).isPresent());
        return resp;
    }

    /**
     * 只返回当前用户在指定 Agent 或方案下的会话。管理员也不放行别人的运行记录。
     *
     * <p>真分页：页大小只受全站上限（100）约束，调用方要多少给多少；
     * {@code history-limit} 仅为「未传 size」时的默认页大小。返回体的 total 是全量 COUNT，
     * 用于前端判断还有没有下一页。
     *
     * @param req 必须带 agentId 或 maId（二选一），以及 current / size
     * @return 按开始时间倒序的会话页
     * @throws BizException 两者都没给，或同时给了
     */
    public PageResult<SessionResp> page(SessionPageReq req) {
        boolean byAgent = req.getAgentId() != null;
        boolean byMa = req.getMaId() != null;
        if (!byAgent && !byMa) {
            throw new BizException(ErrorCode.BAD_REQUEST, "agentId 与 maId 至少填一个");
        }
        if (byAgent && byMa) {
            // 两种入口语义不同（绑根 / 虚拟根），混着查会同时命中绑根会话与方案会话
            throw new BizException(ErrorCode.BAD_REQUEST, "agentId 与 maId 只能填一个");
        }
        SessionQuery query = new SessionQuery();
        query.setCurrent(req.getCurrent());
        query.setSize(historySize(req.getSize()));
        query.setAgentId(req.getAgentId());
        query.setMaId(req.getMaId());
        // 调试左栏只查调试会话：工作台正式对话走独立渠道，两套列表互不串
        query.setEntryChannel(EntryChannel.DEBUG.name());
        // 过滤键只有当前用户。不按租户，也不因 is_admin 放开。不再懒结案。
        long userId = operatorContext.requireUserId();
        query.setUserId(userId);
        PageResult<RunSession> page = sessions.page(query);
        // 整页只按需读方案名：虚拟根行需要，绑根行不需要
        Map<Long, MaDef> maCache = new HashMap<>();
        // Agent 名一次性批量预取：逐行 findById 在 100 条页上就是 100 次主键查询
        Map<Long, String> agentNames = prefetchAgentNames(page.getRecords());
        // 取消行是否还有没写完的一轮：整页一次读，避免逐行把助手正文拉出来
        Map<Long, Boolean> openTurns = openTurnsOf(page.getRecords());
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(item -> toResp(item, maCache, agentNames, openTurns)).toList());
    }

    /**
     * 工作台左栏：当前用户全部正式对话会话，按开始时间倒序。
     *
     * <p>不按 agentId / maId 过滤：换默认方案后历史会话仍要能看到；
     * 渠道过滤与用户隔离由仓储层完成。
     *
     * @param current 页码
     * @param size    页大小；受全站上限约束
     * @return 会话页
     */
    public PageResult<SessionResp> pageWorkbench(long current, long size) {
        SessionQuery query = new SessionQuery();
        query.setCurrent(current);
        query.setSize(historySize(size));
        query.setEntryChannel(EntryChannel.WORKBENCH.name());
        // 工作台分组读 update_time；列表也按它排，清空后「刚刚」才会真正落到今天第一条
        query.setOrderByActivity(true);
        query.setUserId(operatorContext.requireUserId());
        PageResult<RunSession> page = sessions.page(query);
        Map<Long, MaDef> maCache = new HashMap<>();
        Map<Long, String> agentNames = prefetchAgentNames(page.getRecords());
        Map<Long, Boolean> openTurns = openTurnsOf(page.getRecords());
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(item -> toResp(item, maCache, agentNames, openTurns)).toList());
    }

    /**
     * @param id 会话主键
     * @return 详情
     */
    public SessionResp get(Long id) {
        return toResp(access.require(id));
    }

    /**
     * 刷新回放：已落库的权威事件，按 seq 升序。
     *
     * <p>工作流卡片在库里只存骨架（不含节点正文）：这里按卡片里的 {@code runId} 现算节点产出，
     * 让刷新后看到的卡片与实时一致。补全失败时保留骨架，不会整块丢卡。
     *
     * @param id 会话主键
     * @return 全部事件，seq 升序
     */
    public List<EventResp> events(Long id) {
        access.require(id);
        return cardRebuilder.enrich(events.listBySessionId(id).stream()
                .map(TrialSessionQueryService::toEvent).toList());
    }

    /**
     * 进行中一轮的实时快照，供刷新页面后的前端继续「边跑边看」。
     *
     * <p>与 {@link #events(Long)} 的区别：events 读的是已落库的权威事件，要等整轮结束才有内容；
     * 本方法读内存镜像，能立刻反映思考、工具与正文增量，但只在当前进程有效，
     * 且整轮结束后即为空 —— 调用方拿到空列表时应回退到 events。
     *
     * @param id 会话主键
     * @return seq 升序的快照；没有在跑的一轮时为空列表
     */
    public List<EventResp> liveEvents(Long id) {
        access.require(id);
        // 快照装不下整份卡片时会退成骨架：同一条补全口径，刷新「边跑边看」也拿得到节点正文
        return cardRebuilder.enrich(live.snapshot(id));
    }

    /**
     * 取消行里哪些还有没写完的一轮。用户点取消的摘要已经能判定，不再读事件。
     * 其它状态不用查：展示就用库里的 status。
     *
     * @param rows 本页或单条会话
     * @return 会话 id → 最后一轮是否还没写完；读失败的行不在表里，按已写完展示
     */
    Map<Long, Boolean> openTurnsOf(List<RunSession> rows) {
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = new ArrayList<>();
        for (RunSession row : rows) {
            if (row == null || row.getId() == null) {
                continue;
            }
            if (!RunStatus.CANCELLED.name().equals(row.getStatus()) || SessionCancelFace.userCancelled(row)) {
                continue;
            }
            ids.add(row.getId());
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<RunEvent>> grouped = new HashMap<>();
        for (RunEvent mark : events.listClosureMarks(ids)) {
            if (mark == null || mark.getSessionId() == null) {
                continue;
            }
            grouped.computeIfAbsent(mark.getSessionId(), key -> new ArrayList<>()).add(mark);
        }
        Map<Long, Boolean> open = new HashMap<>();
        for (Long id : ids) {
            open.put(id, TurnStillOpen.of(grouped.get(id)));
        }
        return open;
    }

    /**
     * 单条会话 → 接口对象（页内批量场景的简化入口，内部按单条预取）。
     *
     * @param session 领域会话
     * @return 接口对象
     */
    SessionResp toResp(RunSession session) {
        List<RunSession> one = session == null ? List.of() : List.of(session);
        return toResp(session, new HashMap<>(), new HashMap<>(), openTurnsOf(one));
    }

    /**
     * 一次批量取回本页用到的 Agent 名。
     *
     * <p>为什么必须批量：页大小已从 20 放开到最多 100，逐行 {@code findById} 就是最多 100 次
     * 主键查询；这里按去重后的 id 集合取一次，缺失（已删 / 不存在）的 id 显式补空串，
     * 让 {@link #toResp} 不再回落到逐行查询。
     *
     * @param rows 当前页会话
     * @return agentId → 名称（值不为 null）
     */
    Map<Long, String> prefetchAgentNames(List<RunSession> rows) {
        Set<Long> ids = new LinkedHashSet<>();
        for (RunSession row : rows) {
            if (row.getAgentId() != null) {
                ids.add(row.getAgentId());
            }
        }
        Map<Long, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        for (AgentDef def : agents.findByIds(ids)) {
            names.put(def.getId(), def.getName() == null ? "" : def.getName());
        }
        // 软删 / 不存在的 Agent 也要占位，否则 toResp 会为这些行再查一次
        for (Long id : ids) {
            names.putIfAbsent(id, "");
        }
        return names;
    }

    /**
     * 会话 → 接口对象。虚拟根没有 Agent：展示名退回方案名，避免左栏出现空白标题。
     *
     * @param session    领域会话
     * @param maCache    本次请求内的方案缓存；避免整页 N 次主键查询
     * @param agentNames 本次请求内批量预取的 Agent 名；命中即用，未命中才回落单查
     * @param openTurns  取消行里最后一轮是否还没写完；没有的 id 按已写完
     * @return 接口对象
     */
    SessionResp toResp(RunSession session, Map<Long, MaDef> maCache, Map<Long, String> agentNames,
                       Map<Long, Boolean> openTurns) {
        SessionResp resp = new SessionResp();
        resp.setId(session.getId());
        resp.setAgentId(session.getAgentId());
        resp.setMaId(session.getMaDefId());
        String agentName = "";
        if (session.getAgentId() != null) {
            String cached = agentNames.get(session.getAgentId());
            // 页内预取命中；只有单条详情（get）没走预取时才回落到主键查询
            agentName = cached != null ? cached
                    : agents.findById(session.getAgentId()).map(AgentDef::getName).orElse("");
        }
        MaDef ma = session.getMaDefId() == null ? null
                : maCache.computeIfAbsent(session.getMaDefId(), key -> mas.findById(key).orElse(null));
        if (ma != null) {
            resp.setMaCode(ma.getCode());
            resp.setMaName(ma.getName());
        }
        // 方案会话没有 Agent：Agent 名留空，由方案名兜底；旧 Agent 运行仍展示 Agent 名
        resp.setAgentName(agentName.isBlank() && ma != null ? ma.getName() : agentName);
        resp.setRunMode(session.getRunMode());
        resp.setUserId(session.getUserId());
        // 已经写完却被标成取消的历史行，展示回进行中。不改库列。
        boolean turnOpen = session.getId() != null && openTurns != null
                && Boolean.TRUE.equals(openTurns.get(session.getId()));
        resp.setStatus(SessionCancelFace.statusForClient(session, turnOpen));
        resp.setStartTime(session.getStartTime());
        resp.setEndTime(session.getEndTime());
        resp.setMessageCount(session.getMessageCount());
        resp.setSessionTitle(session.getSessionTitle());
        resp.setTitleSource(session.getTitleSource());
        resp.setLastMsgPreview(session.getLastMsgPreview());
        resp.setErrorSummary(session.getErrorSummary());
        resp.setUpdateTime(session.getUpdateTime());
        resp.setEntryChannel(session.getEntryChannel());
        resp.setReceptionMaId(session.getReceptionMaId());
        resp.setStickyRouteKey(session.getStickyRouteKey());
        return resp;
    }

    /**
     * @param event 领域事件
     * @return 接口事件
     */
    public static EventResp toEvent(RunEvent event) {
        EventResp resp = new EventResp();
        resp.setId(event.getId());
        resp.setSessionId(event.getSessionId());
        resp.setSeq(event.getSeq());
        resp.setEventType(event.getEventType());
        resp.setSummary(event.getSummary());
        resp.setContentText(event.getContentText());
        resp.setTokenPrompt(event.getTokenPrompt());
        resp.setTokenCompletion(event.getTokenCompletion());
        resp.setCreateTime(event.getCreateTime());
        return resp;
    }

    /**
     * 解析页大小：语义是「页大小」，不是「总量上限」。
     *
     * <p>旧实现把任意请求都裁到 {@code history-limit}（默认 20），导致左栏永远只有 20 条；
     * 现在交给 {@link HistoryPageSize}：传了 size 就按 size（仍受全站 100 约束），
     * 没传才用配置的默认值。想改「一次看多少」应改前端页大小，而不是在这里缩总量。
     *
     * @param requested 请求的每页条数
     * @return 实际查询条数，落在 [1, 100]
     */
    private long historySize(long requested) {
        return HistoryPageSize.resolve(requested, properties.getHistoryLimit());
    }
}
