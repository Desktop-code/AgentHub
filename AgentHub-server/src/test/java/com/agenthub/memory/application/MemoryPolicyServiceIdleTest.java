package com.agenthub.memory.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.repository.MemoryPolicyRepository;
import com.agenthub.memory.infra.MemoryProperties;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 空闲秒数（ctx_idle_ttl_sec）保存校验：最小 60，中文报错，且默认 7200 不被这里改写。
 * 该列同时是窗口 TTL 与「空闲多久后收口批抽」，非法值必须在保存处挡住。
 */
@ExtendWith(MockitoExtension.class)
class MemoryPolicyServiceIdleTest {

    private static final long AGENT_ID = 5L;

    @Mock
    private MemoryPolicyRepository policies;
    @Mock
    private AgentDefRepository agents;
    @Mock
    private ModelInstanceRepository instances;
    @Mock
    private OperatorContext operatorContext;
    @Mock
    private OperLogWriter operLogWriter;

    private MemoryProperties properties;
    private MemoryPolicyAppService service;

    @BeforeEach
    void setUp() {
        properties = new MemoryProperties();
        service = new MemoryPolicyAppService(policies, agents, instances, properties,
                operatorContext, operLogWriter);
        lenient().when(agents.findById(AGENT_ID)).thenReturn(Optional.of(new AgentDef()));
        lenient().when(operatorContext.requireUserId()).thenReturn(1L);
        lenient().when(policies.findActiveByAgentId(AGENT_ID)).thenReturn(Optional.empty());
        lenient().when(policies.insert(any(), anyLong())).thenReturn(99L);
        lenient().when(policies.findById(anyLong())).thenReturn(Optional.empty());
    }

    /** 默认策略：没有库行时给平台默认 7200，不因本刀改动而变。 */
    @Test
    void defaultsKeep7200() {
        assertEquals(7200, service.effective(AGENT_ID).getCtxIdleTtlSec());
    }

    /** 59 秒：中文报错并拒绝保存，不能悄悄压到 60。 */
    @Test
    void belowMinimumIsRejectedWithChineseMessage() {
        BizException ex = assertThrows(BizException.class, () -> service.save(valid(59)));
        assertTrue(ex.getMessage().contains("空闲时间"), ex.getMessage());
        assertTrue(ex.getMessage().contains("60"), ex.getMessage());
    }

    /** 120 秒：可保存，批抽到期按这个值判定。 */
    @Test
    void validIdleSecondsIsAccepted() {
        when(policies.findById(99L)).thenReturn(Optional.of(valid(120)));
        assertEquals(120, service.save(valid(120)).getCtxIdleTtlSec());
    }

    /**
     * 构造一条字段齐全、仅空闲秒数不同的策略，避免其它字段先撞校验。
     */
    private static MemoryPolicy valid(int idleSec) {
        MemoryPolicy policy = new MemoryPolicy();
        policy.setAgentId(AGENT_ID);
        policy.setEnableShort(1);
        policy.setEnableLong(1);
        policy.setEnableLongExtract(1);
        policy.setLongExtractOnSessionEnd(1);
        policy.setLongExtractOnExplicit(1);
        policy.setMaxShortItems(20);
        policy.setMaxLongItems(100);
        policy.setCtxMaxTurns(12);
        policy.setCtxMaxChars(16000);
        policy.setCtxIdleTtlSec(idleSec);
        policy.setShortDefaultTtlSec(86400);
        return policy;
    }
}
