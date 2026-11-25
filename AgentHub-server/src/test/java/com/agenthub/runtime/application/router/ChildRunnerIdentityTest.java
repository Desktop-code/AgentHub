package com.agenthub.runtime.application.router;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 参与者 id 的唯一性与嵌套上下文传递。所属域：runtime。
 *
 * <p>id 只由 route_key 组成时，两层方案都分同名子专家会被 {@code putIfAbsent} 吞掉；
 * 这里锁定「深度 + 父方案键 + 叶子键」的生成口径，以及跨线程继承方式。
 */
class ChildRunnerIdentityTest {

    @AfterEach
    void clear() {
        while (MaNestedContext.inside()) {
            MaNestedContext.exit();
        }
    }

    @Test
    void rootLayerIdUsesRootPlaceholder() {
        assertEquals("p-0-root-policy", ChildRunner.participantIdOf(0, "", "policy"));
    }

    @Test
    void nestedLayersWithSameLeafKeyStayDistinct() {
        String first = ChildRunner.participantIdOf(1, "enterprise", "policy");
        String second = ChildRunner.participantIdOf(1, "report", "policy");
        assertFalse(first.equals(second), "不同父方案的同一 route_key 不能共用一个 id");
        assertEquals("p-1-enterprise-policy", first);
        assertEquals("p-1-report-policy", second);
    }

    @Test
    void blankKeysDegradeToReadablePlaceholders() {
        assertEquals("p-0-root-unknown", ChildRunner.participantIdOf(0, null, ""));
        assertEquals("p-2-scheme-7", ChildRunner.participantIdOf(2, "scheme", "7"));
    }

    @Test
    void enterKeepsParentKeyOnTopOfStack() {
        MaNestedContext.enter("enterprise");
        assertEquals(1, MaNestedContext.depth());
        assertEquals("enterprise", MaNestedContext.parentKey());
        MaNestedContext.enter("policy-pack");
        assertEquals(2, MaNestedContext.depth());
        assertEquals("policy-pack", MaNestedContext.parentKey());
        MaNestedContext.exit();
        assertEquals("enterprise", MaNestedContext.parentKey());
    }

    @Test
    void callAtCarriesDepthAndParentKeyIntoAnotherThread() throws Exception {
        MaNestedContext.enter("enterprise");
        AtomicReference<Integer> depth = new AtomicReference<>();
        AtomicReference<String> parent = new AtomicReference<>();
        // 池化线程不继承 ThreadLocal：必须由 callAt 显式设置，结束后原值还原
        Thread worker = new Thread(() -> MaNestedContext.callAt(1, "enterprise", () -> {
            depth.set(MaNestedContext.depth());
            parent.set(MaNestedContext.parentKey());
            return null;
        }));
        worker.start();
        worker.join();
        assertEquals(1, depth.get());
        assertEquals("enterprise", parent.get());
        // 主线程上下文不能被 worker 的 enter/exit 污染
        assertEquals(1, MaNestedContext.depth());
        assertEquals("enterprise", MaNestedContext.parentKey());
    }
}
