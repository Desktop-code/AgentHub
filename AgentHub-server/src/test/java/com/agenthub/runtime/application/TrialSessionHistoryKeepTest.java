package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.MaDefRepository;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.runtime.api.dto.SessionPageReq;
import com.agenthub.runtime.api.dto.SessionResp;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.RunStatus;
import com.agenthub.runtime.domain.model.SessionQuery;
import com.agenthub.runtime.domain.model.TitleSource;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import com.agenthub.runtime.infra.agentscope.TrialProperties;
import com.agenthub.workflow.application.WorkflowRunAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 聊过一轮后再开新会话：旧行仍在，列表能返回；新建不再懒结案。所属域：runtime。
 */
@ExtendWith(MockitoExtension.class)
class TrialSessionHistoryKeepTest {

    private static final long USER_ID = 7L;
    private static final long AGENT_ID = 4L;

    @Mock
    private RunEventRepository events;
    @Mock
    private AgentDefRepository agents;
    @Mock
    private TrialAccess access;
    @Mock
    private OperatorContext operatorContext;
    @Mock
    private OperLogWriter operLogWriter;
    @Mock
    private TrialFlight flight;
    @Mock
    private ReActTrialExecutor react;
    @Mock
    private TrialMemoryCoordinator memory;
    @Mock
    private TrialSessionCloser closer;
    @Mock
    private TrialBindingLoader loader;
    @Mock
    private MaTrialSpecLoader maSpecs;
    @Mock
    private MaDefRepository mas;
    @Mock
    private WorkflowRunAppService workflowRuns;
    @Mock
    private TrialObsHooks obs;

    private MemorySessions store;
    private TrialSessionAppService service;

    @BeforeEach
    void setUp() {
        store = new MemorySessions();
        when(operatorContext.requireUserId()).thenReturn(USER_ID);
        when(agents.findById(AGENT_ID)).thenReturn(Optional.of(enabledAgent()));
        TrialProperties properties = new TrialProperties();
        TrialLiveBuffer liveBuffer = new TrialLiveBuffer();
        PlanPendingRegistry pendingPlans = new PlanPendingRegistry();
        ChildPlanApprovalRegistry childApprovals = new ChildPlanApprovalRegistry();
        TrialSessionQueryService query = new TrialSessionQueryService(store, events, agents, access,
                operatorContext, properties, liveBuffer, loader, mas, org.mockito.Mockito.mock(WorkflowCardRebuilder.class));
        TrialSessionLifecycleService lifecycle = new TrialSessionLifecycleService(store, events, agents, access,
                operatorContext, operLogWriter, flight, react, memory, closer, pendingPlans, childApprovals,
                maSpecs, query);
        service = new TrialSessionAppService(store, access, operatorContext, operLogWriter, flight, react,
                closer, liveBuffer, pendingPlans, childApprovals, workflowRuns,
                org.mockito.Mockito.mock(WorkflowReplyResumer.class), org.mockito.Mockito.mock(MaReplyResumer.class),
                obs, query, lifecycle);
    }

    /**
     * 再创建一条时旧行保持 RUNNING，列表两条都在。新建不得逻辑删旧行，也不得结案旧会话。
     */
    @Test
    void freshTurnStaysListedWhenCreatingAnother() {
        long priorId = seedChatted(LocalDateTime.now());
        SessionResp created = service.create(AGENT_ID);

        assertNotEquals(priorId, created.getId());
        RunSession prior = store.findById(priorId).orElseThrow();
        assertEquals(RunStatus.RUNNING.name(), prior.getStatus());
        assertNull(prior.getEndTime());
        assertEquals(1, prior.getMessageCount());
        assertFalse(store.logicDeleted(priorId));
        List<SessionResp> listed = listForAgent();
        assertEquals(2, listed.size());
        assertTrue(listed.stream().anyMatch(row -> priorId == row.getId()
                && RunStatus.RUNNING.name().equals(row.getStatus())));
        assertTrue(listed.stream().anyMatch(row -> created.getId().equals(row.getId())));
        verify(memory, never()).onClosed(any());
    }

    /**
     * 即便旧会话早已超过空闲窗，新建也不会结案、不会抽。
     */
    @Test
    void idlePriorStaysRunningWhenCreating() {
        long priorId = seedChatted(LocalDateTime.now().minusHours(3));
        service.create(AGENT_ID);

        RunSession prior = store.findById(priorId).orElseThrow();
        assertEquals(RunStatus.RUNNING.name(), prior.getStatus());
        assertNull(prior.getEndTime());
        assertEquals(1, prior.getMessageCount());
        assertFalse(store.logicDeleted(priorId));
        List<SessionResp> listed = listForAgent();
        assertEquals(2, listed.size());
        assertTrue(listed.stream().anyMatch(row -> priorId == row.getId()
                && RunStatus.RUNNING.name().equals(row.getStatus())));
        verify(memory, never()).onClosed(any());
        verify(memory, never()).onIdleExtract(any());
        verify(closer, never()).sweep();
        verify(closer, never()).cancel(any(), anyLong());
    }

    /**
     * 模拟已经聊过一轮：有轮次、有预览、仍是 RUNNING。
     *
     * @param updateTime 时钟
     * @return 旧会话主键
     */
    private long seedChatted(LocalDateTime updateTime) {
        RunSession session = new RunSession();
        session.setAgentId(AGENT_ID);
        session.setRunMode("REACT");
        session.setUserId(USER_ID);
        session.setStatus(RunStatus.RUNNING.name());
        session.setStartTime(updateTime);
        session.setUpdateTime(updateTime);
        session.setMessageCount(1);
        session.setLastMsgPreview("查一下订单");
        return store.insert(session, USER_ID);
    }

    /**
     * @return 当前用户、当前 Agent 的列表，含已结案
     */
    private List<SessionResp> listForAgent() {
        SessionPageReq req = new SessionPageReq();
        req.setAgentId(AGENT_ID);
        req.setCurrent(1);
        req.setSize(20);
        return service.page(req).getRecords();
    }

    private static AgentDef enabledAgent() {
        AgentDef agent = new AgentDef();
        agent.setId(AGENT_ID);
        agent.setName("演示");
        agent.setRunMode("REACT");
        agent.setStatus(AgentStatus.ENABLED);
        return agent;
    }

    /**
     * 只给本测试用的会话表。逻辑删除只打标，分页看不到已删行，也不按状态过滤。
     */
    private static final class MemorySessions implements RunSessionRepository {

        private final Map<Long, RunSession> rows = new LinkedHashMap<>();
        private final Set<Long> deleted = new HashSet<>();
        private long nextId = 1L;

        /**
         * @param id 会话主键
         * @return 是否被逻辑删除
         */
        boolean logicDeleted(long id) {
            return deleted.contains(id);
        }

        /** {@inheritDoc} */
        @Override
        public PageResult<RunSession> page(SessionQuery query) {
            List<RunSession> matched = new ArrayList<>();
            for (RunSession row : rows.values()) {
                if (deleted.contains(row.getId())) {
                    continue;
                }
                if (query.getUserId() != null && !query.getUserId().equals(row.getUserId())) {
                    continue;
                }
                if (query.getAgentId() != null && !query.getAgentId().equals(row.getAgentId())) {
                    continue;
                }
                matched.add(row);
            }
            matched.sort(Comparator.comparing(RunSession::getStartTime, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(RunSession::getId, Comparator.reverseOrder()));
            long current = Math.max(query.getCurrent(), 1);
            long size = Math.min(Math.max(query.getSize(), 1), 100);
            int from = (int) Math.min(matched.size(), (current - 1) * size);
            int to = (int) Math.min(matched.size(), from + size);
            List<RunSession> slice = new ArrayList<>();
            for (RunSession row : matched.subList(from, to)) {
                slice.add(copy(row));
            }
            return PageResult.of(matched.size(), current, size, slice);
        }

        /** {@inheritDoc} */
        @Override
        public Optional<RunSession> findById(Long id) {
            if (id == null || deleted.contains(id) || !rows.containsKey(id)) {
                return Optional.empty();
            }
            return Optional.of(copy(rows.get(id)));
        }

        /** {@inheritDoc} */
        @Override
        public List<RunSession> listRunning(Long userId, Long agentId) {
            List<RunSession> running = new ArrayList<>();
            for (RunSession row : rows.values()) {
                if (deleted.contains(row.getId()) || !RunStatus.RUNNING.name().equals(row.getStatus())) {
                    continue;
                }
                if (userId != null && !userId.equals(row.getUserId())) {
                    continue;
                }
                if (agentId != null && !agentId.equals(row.getAgentId())) {
                    continue;
                }
                running.add(copy(row));
            }
            running.sort(Comparator.comparing(RunSession::getId));
            return running;
        }

        /** {@inheritDoc} */
        @Override
        public Long insert(RunSession session, Long operatorId) {
            long id = nextId++;
            RunSession stored = copy(session);
            LocalDateTime now = LocalDateTime.now();
            stored.setId(id);
            if (stored.getStartTime() == null) {
                stored.setStartTime(now);
            }
            if (stored.getUpdateTime() == null) {
                stored.setUpdateTime(now);
            }
            if (stored.getCreateTime() == null) {
                stored.setCreateTime(now);
            }
            if (stored.getMessageCount() == null) {
                stored.setMessageCount(0);
            }
            stored.setCreatedBy(operatorId);
            stored.setUpdatedBy(operatorId);
            rows.put(id, stored);
            return id;
        }

        /** {@inheritDoc} */
        @Override
        public void updateOutcome(RunSession session, Long operatorId) {
            RunSession stored = rows.get(session.getId());
            stored.setStatus(session.getStatus());
            // 与仓储实现一致：进行中不写 ended_at，只在结案传入非空时落库
            if (session.getEndTime() != null) {
                stored.setEndTime(session.getEndTime());
            }
            stored.setMessageCount(session.getMessageCount());
            stored.setSessionTitle(session.getSessionTitle());
            stored.setTitleSource(session.getTitleSource());
            stored.setLastMsgPreview(session.getLastMsgPreview());
            stored.setErrorSummary(session.getErrorSummary());
            stored.setUpdatedBy(operatorId);
            stored.setUpdateTime(LocalDateTime.now());
        }

        /** {@inheritDoc} */
        @Override
        public void updateTitle(Long id, String title, String titleSource, Long operatorId) {
            RunSession stored = rows.get(id);
            stored.setSessionTitle(title);
            stored.setTitleSource(titleSource);
            stored.setUpdatedBy(operatorId);
            stored.setUpdateTime(LocalDateTime.now());
        }

        /**
         * {@inheritDoc}
         * 与真实仓储同一套条件：只有当前无标题且未被手改时才写入，返回是否命中。
         */
        @Override
        public boolean updateAutoTitleIfAbsent(Long id, String title, Long operatorId) {
            RunSession stored = rows.get(id);
            if (stored == null) {
                return false;
            }
            boolean hasTitle = stored.getSessionTitle() != null && !stored.getSessionTitle().isBlank();
            if (hasTitle || TitleSource.MANUAL.name().equals(stored.getTitleSource())) {
                return false;
            }
            stored.setSessionTitle(title);
            stored.setTitleSource(TitleSource.AUTO.name());
            stored.setUpdatedBy(operatorId);
            stored.setUpdateTime(LocalDateTime.now());
            return true;
        }

        /** {@inheritDoc} */
        @Override
        public void updateStickyRoute(Long id, String routeKey, Long agentId, Long operatorId) {
            RunSession stored = rows.get(id);
            stored.setStickyRouteKey(routeKey);
            stored.setStickyAgentId(agentId);
            stored.setUpdatedBy(operatorId);
            stored.setUpdateTime(LocalDateTime.now());
        }

        /** {@inheritDoc} */
        @Override
        public void resetMessages(Long id, Long operatorId) {
            RunSession stored = rows.get(id);
            stored.setStatus(RunStatus.RUNNING.name());
            stored.setMessageCount(0);
            stored.setLastMsgPreview("");
            stored.setErrorSummary("");
            stored.setEndTime(null);
            stored.setUpdatedBy(operatorId);
            stored.setUpdateTime(LocalDateTime.now());
        }

        /**
         * {@inheritDoc}
         * 与库实现同一条件：仍是 RUNNING 且 update_time 没被别的写入推进，才收成 CANCELLED。
         * 不改轮次、标题、预览和错误摘要。
         */
        @Override
        public boolean interruptIfUnchanged(Long id, LocalDateTime expectedUpdateTime, LocalDateTime endedAt) {
            if (id == null || endedAt == null || deleted.contains(id)) {
                return false;
            }
            RunSession stored = rows.get(id);
            if (stored == null || !RunStatus.RUNNING.name().equals(stored.getStatus())) {
                return false;
            }
            if (expectedUpdateTime == null) {
                if (stored.getUpdateTime() != null) {
                    return false;
                }
            } else if (!expectedUpdateTime.equals(stored.getUpdateTime())) {
                return false;
            }
            stored.setStatus(RunStatus.CANCELLED.name());
            stored.setEndTime(endedAt);
            stored.setUpdatedBy(0L);
            stored.setUpdateTime(endedAt);
            return true;
        }

        /** {@inheritDoc} */
        @Override
        public void logicDelete(Long id) {
            deleted.add(id);
        }

        private static RunSession copy(RunSession source) {
            RunSession session = new RunSession();
            session.setId(source.getId());
            session.setAgentId(source.getAgentId());
            session.setRunMode(source.getRunMode());
            session.setUserId(source.getUserId());
            session.setStatus(source.getStatus());
            session.setStartTime(source.getStartTime());
            session.setEndTime(source.getEndTime());
            session.setMessageCount(source.getMessageCount());
            session.setSessionTitle(source.getSessionTitle());
            session.setTitleSource(source.getTitleSource());
            session.setLastMsgPreview(source.getLastMsgPreview());
            session.setErrorSummary(source.getErrorSummary());
            session.setCreatedBy(source.getCreatedBy());
            session.setCreateTime(source.getCreateTime());
            session.setUpdatedBy(source.getUpdatedBy());
            session.setUpdateTime(source.getUpdateTime());
            return session;
        }
    }
}
