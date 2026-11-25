package com.agenthub.agent.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.common.error.BizException;
import com.agenthub.common.config.RuntimeLimitsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 保存运行限制：空可通过，超顶拒绝。
 */
class AgentLimitValidatorTest {

    private AgentLimitValidator validator;
    private RuntimeLimitsProperties props;

    @BeforeEach
    void setUp() {
        props = new RuntimeLimitsProperties();
        validator = new AgentLimitValidator(props);
    }

    @Test
    void nullMeansFollowPlatform() {
        AgentDef agent = new AgentDef();
        validator.apply(agent, null, null, null, null, null, null, null, null, null);
        assertNull(agent.getTimeoutMs());
        assertNull(agent.getMaxRounds());
    }

    @Test
    void withinCapIsStored() {
        AgentDef agent = new AgentDef();
        validator.apply(agent, 60_000L, 4, null, null, null, null, null, null, null);
        assertEquals(60_000L, agent.getTimeoutMs());
        assertEquals(4, agent.getMaxRounds());
    }

    @Test
    void overCapIsRejected() {
        AgentDef agent = new AgentDef();
        long over = props.getMaxRounds().getMax() + 1;
        BizException ex = assertThrows(BizException.class,
                () -> validator.apply(agent, null, (int) over, null, null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("maxRounds"));
        assertTrue(ex.getMessage().contains("硬顶"));
    }
}
