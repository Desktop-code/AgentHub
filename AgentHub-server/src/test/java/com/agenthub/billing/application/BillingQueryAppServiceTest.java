package com.agenthub.billing.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.billing.api.dto.DailyPageReq;
import com.agenthub.billing.api.dto.DailyResp;
import com.agenthub.billing.api.dto.LedgerPageReq;
import com.agenthub.billing.api.dto.LedgerResp;
import com.agenthub.billing.domain.model.TokenDaily;
import com.agenthub.billing.domain.model.TokenLedger;
import com.agenthub.billing.domain.repository.TokenDailyRepository;
import com.agenthub.billing.domain.repository.TokenLedgerRepository;
import com.agenthub.common.api.PageResult;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.model.ModelProvider;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.model.domain.repository.ModelProviderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用量查询拼名与删/停标记。所属域：billing。
 * 有行保留真名；deleted 优先于 status 停用；无行才占位「已删除模型」。
 */
@ExtendWith(MockitoExtension.class)
class BillingQueryAppServiceTest {

    @Mock
    private TokenLedgerRepository ledgers;
    @Mock
    private TokenDailyRepository daily;
    @Mock
    private AgentDefRepository agents;
    @Mock
    private ModelInstanceRepository instances;
    @Mock
    private ModelProviderRepository providers;

    @InjectMocks
    private BillingQueryAppService service;

    /**
     * 软删：保留真名 + modelDeleted；即使 status=0 也不叠 modelDisabled。
     */
    @Test
    void ledgers_keepsRealNameWhenModelSoftDeleted() {
        TokenLedger row = ledger(11L, 88L);
        when(ledgers.page(any())).thenReturn(PageResult.of(1, 1, 10, List.of(row)));
        when(agents.findByIdIncludingDeleted(anyLong())).thenReturn(Optional.empty());
        when(instances.findByIdIncludingDeleted(88L))
                .thenReturn(Optional.of(instance(88L, 3L, "旧模型", 1, 0)));
        ModelProvider provider = new ModelProvider();
        provider.setId(3L);
        provider.setName("通义");
        when(providers.findById(3L)).thenReturn(Optional.of(provider));

        LedgerResp resp = service.ledgers(new LedgerPageReq()).getRecords().get(0);

        assertEquals("旧模型", resp.getModelInstanceName());
        assertEquals("通义", resp.getProviderName());
        assertTrue(Boolean.TRUE.equals(resp.getModelDeleted()));
        assertNull(resp.getModelDisabled());
        verify(instances).findByIdIncludingDeleted(88L);
        verify(instances, never()).findById(any());
    }

    /**
     * 查无行：占位 + modelDeleted，列表不抛异常。
     */
    @Test
    void daily_usesPlaceholderWhenModelMissing() {
        TokenDaily row = dailyRow(22L, 99L);
        when(daily.page(any())).thenReturn(PageResult.of(1, 1, 10, List.of(row)));
        when(agents.findByIdIncludingDeleted(anyLong())).thenReturn(Optional.empty());
        when(instances.findByIdIncludingDeleted(99L)).thenReturn(Optional.empty());

        DailyResp resp = service.daily(new DailyPageReq()).getRecords().get(0);

        assertEquals(BillingChecks.DELETED_MODEL_NAME, resp.getModelInstanceName());
        assertTrue(Boolean.TRUE.equals(resp.getModelDeleted()));
        assertNull(resp.getModelDisabled());
    }

    /**
     * 未删仅停用：真名 + modelDisabled，不打 deleted。
     */
    @Test
    void ledgers_marksDisabledWhenStatusOff() {
        TokenLedger row = ledger(11L, 7L);
        when(ledgers.page(any())).thenReturn(PageResult.of(1, 1, 10, List.of(row)));
        when(agents.findByIdIncludingDeleted(anyLong())).thenReturn(Optional.empty());
        when(instances.findByIdIncludingDeleted(7L))
                .thenReturn(Optional.of(instance(7L, 2L, "GPT-停用", 0, 0)));
        ModelProvider provider = new ModelProvider();
        provider.setId(2L);
        provider.setName("OpenAI");
        when(providers.findById(2L)).thenReturn(Optional.of(provider));

        LedgerResp resp = service.ledgers(new LedgerPageReq()).getRecords().get(0);

        assertEquals("GPT-停用", resp.getModelInstanceName());
        assertEquals("OpenAI", resp.getProviderName());
        assertNull(resp.getModelDeleted());
        assertTrue(Boolean.TRUE.equals(resp.getModelDisabled()));
    }

    /**
     * 未删启用：正常展示名，无删/停标记。
     */
    @Test
    void ledgers_keepsLiveModelName() {
        TokenLedger row = ledger(11L, 7L);
        when(ledgers.page(any())).thenReturn(PageResult.of(1, 1, 10, List.of(row)));
        when(agents.findByIdIncludingDeleted(anyLong())).thenReturn(Optional.empty());
        when(instances.findByIdIncludingDeleted(7L))
                .thenReturn(Optional.of(instance(7L, 2L, "GPT-试用", 0, 1)));
        ModelProvider provider = new ModelProvider();
        provider.setId(2L);
        provider.setName("OpenAI");
        when(providers.findById(2L)).thenReturn(Optional.of(provider));

        LedgerResp resp = service.ledgers(new LedgerPageReq()).getRecords().get(0);

        assertEquals("GPT-试用", resp.getModelInstanceName());
        assertEquals("OpenAI", resp.getProviderName());
        assertNull(resp.getModelDeleted());
        assertNull(resp.getModelDisabled());
    }

    /**
     * Agent 软删：保留真名 + agentDeleted；即使 status=0 也不叠 agentDisabled。
     */
    @Test
    void ledgers_keepsRealNameWhenAgentSoftDeleted() {
        TokenLedger row = ledger(11L, 88L);
        when(ledgers.page(any())).thenReturn(PageResult.of(1, 1, 10, List.of(row)));
        when(agents.findByIdIncludingDeleted(11L))
                .thenReturn(Optional.of(agent(11L, "旧助手", 1, 0)));
        when(instances.findByIdIncludingDeleted(88L)).thenReturn(Optional.empty());

        LedgerResp resp = service.ledgers(new LedgerPageReq()).getRecords().get(0);

        assertEquals("旧助手", resp.getAgentName());
        assertTrue(Boolean.TRUE.equals(resp.getAgentDeleted()));
        assertNull(resp.getAgentDisabled());
    }

    /**
     * Agent 未删仅停用：真名 + agentDisabled，不打 agentDeleted。
     */
    @Test
    void daily_marksAgentDisabledWhenStatusOff() {
        TokenDaily row = dailyRow(22L, 99L);
        when(daily.page(any())).thenReturn(PageResult.of(1, 1, 10, List.of(row)));
        when(agents.findByIdIncludingDeleted(22L))
                .thenReturn(Optional.of(agent(22L, "停用助手", 0, 0)));
        when(instances.findByIdIncludingDeleted(99L)).thenReturn(Optional.empty());

        DailyResp resp = service.daily(new DailyPageReq()).getRecords().get(0);

        assertEquals("停用助手", resp.getAgentName());
        assertNull(resp.getAgentDeleted());
        assertTrue(Boolean.TRUE.equals(resp.getAgentDisabled()));
    }

    /**
     * Agent 真的查无行（硬删）：名称为空、不打标记，由前端显示「未知 Agent」，查询不抛异常。
     */
    @Test
    void ledgers_keepsNameEmptyWhenAgentMissing() {
        TokenLedger row = ledger(11L, 88L);
        when(ledgers.page(any())).thenReturn(PageResult.of(1, 1, 10, List.of(row)));
        when(agents.findByIdIncludingDeleted(11L)).thenReturn(Optional.empty());
        when(instances.findByIdIncludingDeleted(88L)).thenReturn(Optional.empty());

        LedgerResp resp = service.ledgers(new LedgerPageReq()).getRecords().get(0);

        assertNull(resp.getAgentName());
        assertNull(resp.getAgentDeleted());
        assertNull(resp.getAgentDisabled());
    }

    private static TokenLedger ledger(Long agentId, Long modelInstanceId) {
        TokenLedger row = new TokenLedger();
        row.setId(1L);
        row.setBizDate(LocalDate.of(2026, 10, 30));
        row.setAgentId(agentId);
        row.setModelInstanceId(modelInstanceId);
        row.setChannel("TRIAL");
        row.setTokenPrompt(1);
        row.setTokenCompletion(2);
        row.setTokenTotal(3);
        row.setStatus(1);
        return row;
    }

    private static TokenDaily dailyRow(Long agentId, Long modelInstanceId) {
        TokenDaily row = new TokenDaily();
        row.setId(2L);
        row.setBizDate(LocalDate.of(2026, 10, 30));
        row.setAgentId(agentId);
        row.setModelInstanceId(modelInstanceId);
        row.setChannel("TRIAL");
        row.setTokenPrompt(1L);
        row.setTokenCompletion(2L);
        row.setTokenTotal(3L);
        row.setStatus(1);
        return row;
    }

    private static AgentDef agent(Long id, String name, int deleted, int status) {
        AgentDef agent = new AgentDef();
        agent.setId(id);
        agent.setName(name);
        agent.setDeleted(deleted);
        agent.setStatus(status);
        return agent;
    }

    private static ModelInstance instance(Long id, Long providerId, String name, int deleted, int status) {
        ModelInstance instance = new ModelInstance();
        instance.setId(id);
        instance.setProviderId(providerId);
        instance.setDisplayName(name);
        instance.setDeleted(deleted);
        instance.setStatus(status);
        return instance;
    }
}

