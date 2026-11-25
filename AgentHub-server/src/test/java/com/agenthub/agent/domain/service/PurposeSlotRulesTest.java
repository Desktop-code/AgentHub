package com.agenthub.agent.domain.service;

import com.agenthub.agent.domain.model.AgentModelBind;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelInstance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 槽容量与有效用途、ROUTER 模式约束。
 */
class PurposeSlotRulesTest {

    @Test
    void maxPerSlotIsThree() {
        assertEquals(3, PurposeSlotRules.MAX_PER_SLOT);
    }

    @Test
    void chatRequiresAtLeastOne() {
        assertTrue(PurposeSlotRules.requiresAtLeastOne(ModelCapability.CHAT));
        assertFalse(PurposeSlotRules.requiresAtLeastOne(ModelCapability.MEMORY));
        assertFalse(PurposeSlotRules.requiresAtLeastOne(ModelCapability.SUMMARY));
    }

    @Test
    void routerOnlyInRouterMode() {
        assertTrue(PurposeSlotRules.purposeAllowedForMode(ModelCapability.CHAT, "REACT"));
        assertFalse(PurposeSlotRules.purposeAllowedForMode(ModelCapability.ROUTER, "REACT"));
        assertTrue(PurposeSlotRules.purposeAllowedForMode(ModelCapability.ROUTER, "ROUTER"));
    }

    /**
     * EMBEDDING / RERANK 不得从「模型配置」入口写入：前者跟知识库向量实例，后者在「知识库」Tab。
     * 其余生成 / 多模态 / 音频 / 安全用途仍可绑。
     */
    @Test
    void embeddingAndRerankNotBindableFromSlotEntry() {
        assertFalse(PurposeSlotRules.agentBindable(ModelCapability.EMBEDDING));
        assertFalse(PurposeSlotRules.agentBindable(ModelCapability.RERANK));
        assertTrue(PurposeSlotRules.agentBindable(ModelCapability.CHAT));
        assertTrue(PurposeSlotRules.agentBindable(ModelCapability.SUMMARY));
        assertTrue(PurposeSlotRules.agentBindable(ModelCapability.MEMORY));
        assertTrue(PurposeSlotRules.agentBindable(ModelCapability.VISION));
    }

    /**
     * 重排是单槽：容量 1；其它槽沿用每槽 3。
     */
    @Test
    void rerankSlotCapacityIsOne() {
        assertEquals(1, PurposeSlotRules.RERANK_MAX);
        assertEquals(1, PurposeSlotRules.slotCapacity(ModelCapability.RERANK));
        assertEquals(PurposeSlotRules.MAX_PER_SLOT, PurposeSlotRules.slotCapacity(ModelCapability.CHAT));
        assertEquals(PurposeSlotRules.MAX_PER_SLOT, PurposeSlotRules.slotCapacity(ModelCapability.MEMORY));
    }

    @Test
    void effectivePurposePrefersBindOverride() {
        AgentModelBind bind = new AgentModelBind();
        bind.setPurpose("MEMORY");
        ModelInstance instance = new ModelInstance();
        instance.setCapability("CHAT");
        assertEquals(ModelCapability.MEMORY, PurposeSlotRules.effectivePurpose(bind, instance).orElseThrow());
    }

    /**
     * 槽校验：RERANK 只认 RERANK；旧码 EMBED 可对 EMBEDDING；CHAT 不得冒充 RERANK。
     */
    @Test
    void instanceMatchesSlotExactCapability() {
        ModelInstance rerank = new ModelInstance();
        rerank.setCapability("RERANK");
        ModelInstance embedLegacy = new ModelInstance();
        embedLegacy.setCapability("EMBED");
        ModelInstance chat = new ModelInstance();
        chat.setCapability("CHAT");

        assertTrue(PurposeSlotRules.instanceMatchesSlot(rerank, ModelCapability.RERANK));
        assertFalse(PurposeSlotRules.instanceMatchesSlot(chat, ModelCapability.RERANK));
        assertFalse(PurposeSlotRules.instanceMatchesSlot(embedLegacy, ModelCapability.RERANK));
        assertTrue(PurposeSlotRules.instanceMatchesSlot(embedLegacy, ModelCapability.EMBEDDING));
        assertFalse(PurposeSlotRules.instanceMatchesSlot(chat, ModelCapability.EMBEDDING));
    }

    @Test
    void sortByPriorityThenId() {
        AgentModelBind a = bind(2L, 20);
        AgentModelBind b = bind(1L, 10);
        AgentModelBind c = bind(3L, 10);
        List<AgentModelBind> sorted = PurposeSlotRules.sortByPriority(List.of(a, c, b));
        assertEquals(List.of(1L, 3L, 2L), sorted.stream().map(AgentModelBind::getId).toList());
        assertEquals(1, PurposeSlotRules.slotRank(sorted, 1L));
        assertEquals(2, PurposeSlotRules.slotRank(sorted, 3L));
        assertEquals(3, PurposeSlotRules.slotRank(sorted, 2L));
    }

    private static AgentModelBind bind(long id, int priority) {
        AgentModelBind bind = new AgentModelBind();
        bind.setId(id);
        bind.setPriority(priority);
        return bind;
    }
}
