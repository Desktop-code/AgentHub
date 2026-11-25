package com.agenthub.memory.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L1 前的闸门：只挡明显不像存储意图的句子，陈述句必须放行交给 L1。
 */
class ExplicitIntentGateTest {

    @Test
    void pureQuestionsAreBlocked() {
        assertFalse(ExplicitIntentGate.shouldClassify("今天天气怎么样？"));
        assertFalse(ExplicitIntentGate.shouldClassify("记住了吗"));
        assertFalse(ExplicitIntentGate.shouldClassify("你还记得吗"));
        assertFalse(ExplicitIntentGate.shouldClassify("我的订单到哪了"));
    }

    @Test
    void shortNoiseAndSmallTalkAreBlocked() {
        assertFalse(ExplicitIntentGate.shouldClassify("你好"));
        assertFalse(ExplicitIntentGate.shouldClassify("嗯"));
        assertFalse(ExplicitIntentGate.shouldClassify("谢谢！"));
        assertFalse(ExplicitIntentGate.shouldClassify("好的"));
        assertFalse(ExplicitIntentGate.shouldClassify("。。。"));
        assertFalse(ExplicitIntentGate.shouldClassify(""));
        assertFalse(ExplicitIntentGate.shouldClassify(null));
    }

    @Test
    void actionCommandsAreBlocked() {
        assertFalse(ExplicitIntentGate.shouldClassify("帮我查一下订单"));
        assertFalse(ExplicitIntentGate.shouldClassify("打开设置页"));
    }

    @Test
    void statementsAreAllowedForL1() {
        // 这是回归重点：不能在这里替 L1 判定，必须放行后由 L1 输出 normal
        assertTrue(ExplicitIntentGate.shouldClassify("我居住地是广州"));
        assertTrue(ExplicitIntentGate.shouldClassify("我在深圳上班"));
    }
}
