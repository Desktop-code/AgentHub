package com.agenthub.runtime.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 异步标题生成：只在无标题时出站、模型异常不外抛、清洗后为空不写库。
 * 直接调用方法而非走代理，因此不依赖真实线程池；这里验的是业务判定。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionTitleGeneratorTest {

    private static final long SESSION_ID = 7L;
    private static final long AGENT_ID = 9L;
    private static final long USER_ID = 100L;

    @Mock
    private ThreadPoolTaskExecutor lightTaskExecutor;
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
        service = new SessionTitleGeneratorAppService(lightTaskExecutor, summaryResolver, memoryPolicies,
                models, sessions, properties);
    }

    @Test
    void generatesAndCleansTitleWhenAbsent() {
        stubSession(null, TitleSource.AUTO.name());
        stubModel("「订单查询助手」。");
        when(sessions.updateAutoTitleIfAbsent(eq(SESSION_ID), anyString(), eq(USER_ID))).thenReturn(true);

        service.asyncGenerateTitle(SESSION_ID, AGENT_ID, "帮我查订单", "你的订单已发货");

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(sessions).updateAutoTitleIfAbsent(eq(SESSION_ID), title.capture(), eq(USER_ID));
        assertEquals("订单查询助手", title.getValue());
    }

    @Test
    void skipsWhenTitleAlreadyExists() {
        stubSession("已有标题", TitleSource.AUTO.name());

        service.asyncGenerateTitle(SESSION_ID, AGENT_ID, "帮我查订单", "你的订单已发货");

        verify(models, never()).complete(anyLong(), any(), anyLong(), anyLong(), anyString(), anyString());
        verify(sessions, never()).updateAutoTitleIfAbsent(anyLong(), anyString(), anyLong());
    }

    @Test
    void neverOverwritesManualTitle() {
        // 用户已手改：即使 session_title 恰好被清空，MANUAL 标记也必须拦住自动写入
        stubSession("", TitleSource.MANUAL.name());

        service.asyncGenerateTitle(SESSION_ID, AGENT_ID, "帮我查订单", "你的订单已发货");

        verify(models, never()).complete(anyLong(), any(), anyLong(), anyLong(), anyString(), anyString());
        verify(sessions, never()).updateAutoTitleIfAbsent(anyLong(), anyString(), anyLong());
    }

    @Test
    void skipsWhenSwitchOff() {
        properties.setAutoTitleEnabled(false);

        service.asyncGenerateTitle(SESSION_ID, AGENT_ID, "帮我查订单", "你的订单已发货");

        verify(sessions, never()).findById(anyLong());
        verify(models, never()).complete(anyLong(), any(), anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void emptyTitleAfterCleanIsNotWritten() {
        stubSession(null, TitleSource.AUTO.name());
        // 只有标点：清洗后为空，宁可不写标题也不要写空串
        stubModel("。。！");

        service.asyncGenerateTitle(SESSION_ID, AGENT_ID, "帮我查订单", "你的订单已发货");

        verify(sessions, never()).updateAutoTitleIfAbsent(anyLong(), anyString(), anyLong());
    }

    @Test
    void modelFailureIsSwallowed() {
        stubSession(null, TitleSource.AUTO.name());
        when(memoryPolicies.effective(AGENT_ID)).thenReturn(new MemoryPolicy());
        when(summaryResolver.requireSummary(any(), eq(AGENT_ID))).thenReturn(33L);
        when(models.complete(anyLong(), any(), anyLong(), anyLong(), anyString(), anyString()))
                .thenThrow(new BizException(ErrorCode.BAD_REQUEST, "摘要或抽取模型调用失败"));

        // 标题是锦上添花：任何失败都不得冒泡到已结束的一轮对话
        assertDoesNotThrow(() -> service.asyncGenerateTitle(SESSION_ID, AGENT_ID, "问", "答"));
        verify(sessions, never()).updateAutoTitleIfAbsent(anyLong(), anyString(), anyLong());
    }

    @Test
    void skipsWhenSessionGone() {
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.empty());

        service.asyncGenerateTitle(SESSION_ID, AGENT_ID, "问", "答");

        verify(models, never()).complete(anyLong(), any(), anyLong(), anyLong(), anyString(), anyString());
    }

    private void stubSession(String title, String source) {
        RunSession session = new RunSession();
        session.setId(SESSION_ID);
        session.setAgentId(AGENT_ID);
        session.setUserId(USER_ID);
        session.setSessionTitle(title);
        session.setTitleSource(source);
        when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(session));
    }

    private void stubModel(String rawTitle) {
        when(memoryPolicies.effective(AGENT_ID)).thenReturn(new MemoryPolicy());
        when(summaryResolver.requireSummary(any(), eq(AGENT_ID))).thenReturn(33L);
        when(models.complete(anyLong(), any(), anyLong(), anyLong(), anyString(), anyString())).thenReturn(rawTitle);
    }
}
