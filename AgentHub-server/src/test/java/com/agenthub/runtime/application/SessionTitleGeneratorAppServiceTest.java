package com.agenthub.runtime.application;

import com.agenthub.memory.application.MemoryPolicyAppService;
import com.agenthub.memory.application.SummaryModelResolver;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.service.MemoryModelClient;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.TitleSource;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import com.agenthub.runtime.infra.agentscope.TrialProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 首轮异步标题：虚拟根 agentId 可空仍可生成；已有标题 / MANUAL 不覆盖；失败不落首问。
 */
@ExtendWith(MockitoExtension.class)
class SessionTitleGeneratorAppServiceTest {

    @Mock
    private SummaryModelResolver summaryResolver;
    @Mock
    private MemoryPolicyAppService memoryPolicies;
    @Mock
    private MemoryModelClient models;
    @Mock
    private RunSessionRepository sessions;

    private TrialProperties properties;
    private SessionTitleGeneratorAppService service;

    @BeforeEach
    void setUp() {
        properties = new TrialProperties();
        properties.setAutoTitleEnabled(true);
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(1);
        pool.setMaxPoolSize(1);
        pool.setQueueCapacity(1);
        pool.initialize();
        service = new SessionTitleGeneratorAppService(pool, summaryResolver, memoryPolicies, models, sessions, properties);
    }

    @Test
    void nullAgentIdStillGeneratesViaPlatformSummary() {
        RunSession session = blankSession(11L);
        when(sessions.findById(11L)).thenReturn(Optional.of(session));
        when(memoryPolicies.effective(isNull())).thenReturn(new MemoryPolicy());
        when(summaryResolver.requireSummary(org.mockito.ArgumentMatchers.any(), isNull())).thenReturn(99L);
        when(models.complete(eq(0L), eq(11L), eq(7L), eq(99L), anyString(), anyString()))
                .thenReturn("天气出行");
        when(sessions.updateAutoTitleIfAbsent(11L, "天气出行", 7L)).thenReturn(true);

        service.asyncGenerateTitle(11L, null, "明天北京下雨吗", "建议带伞出门");

        verify(sessions).updateAutoTitleIfAbsent(11L, "天气出行", 7L);
        verify(models).complete(eq(0L), eq(11L), eq(7L), eq(99L), anyString(), anyString());
    }

    @Test
    void existingTitleSkipsOutbound() {
        RunSession session = blankSession(12L);
        session.setSessionTitle("已有标题");
        session.setTitleSource(TitleSource.AUTO.name());
        when(sessions.findById(12L)).thenReturn(Optional.of(session));

        service.asyncGenerateTitle(12L, 3L, "问句", "答句");

        verify(models, never()).complete(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString());
        verify(sessions, never()).updateAutoTitleIfAbsent(anyLong(), anyString(), anyLong());
    }

    @Test
    void manualTitleSkipsEvenWhenBlankText() {
        RunSession session = blankSession(13L);
        session.setSessionTitle("");
        session.setTitleSource(TitleSource.MANUAL.name());
        when(sessions.findById(13L)).thenReturn(Optional.of(session));

        service.asyncGenerateTitle(13L, 3L, "问句", "答句");

        verify(models, never()).complete(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString());
        verify(sessions, never()).updateAutoTitleIfAbsent(anyLong(), anyString(), anyLong());
    }

    @Test
    void emptyCleanResultDoesNotPersistFirstQuestion() {
        RunSession session = blankSession(14L);
        when(sessions.findById(14L)).thenReturn(Optional.of(session));
        when(memoryPolicies.effective(3L)).thenReturn(new MemoryPolicy());
        when(summaryResolver.requireSummary(org.mockito.ArgumentMatchers.any(), eq(3L))).thenReturn(99L);
        when(models.complete(eq(3L), eq(14L), eq(7L), eq(99L), anyString(), anyString()))
                .thenReturn("???");

        service.asyncGenerateTitle(14L, 3L, "很长的首问要不要当成标题", "答句");

        verify(sessions, never()).updateAutoTitleIfAbsent(anyLong(), anyString(), anyLong());
    }

    private static RunSession blankSession(long id) {
        RunSession session = new RunSession();
        session.setId(id);
        session.setUserId(7L);
        session.setSessionTitle(null);
        session.setTitleSource(null);
        return session;
    }
}
