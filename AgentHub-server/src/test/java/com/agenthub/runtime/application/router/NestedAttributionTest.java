package com.agenthub.runtime.application.router;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 嵌套归属只认最内层。所属域：runtime 的应用层单测。
 *
 * <p>外层包装器不得在同一次调用里把子专家的增量改挂成方案。
 */
class NestedAttributionTest {

    @Test
    void outerDoesNotClaimWhileInnerEmits() {
        List<String> claimed = new ArrayList<>();

        NestedAttribution.emit(() -> claimed.add("child"), () ->
                NestedAttribution.emit(() -> claimed.add("scheme"), () -> claimed.add("out")));

        assertEquals(List.of("child", "out"), claimed);
    }

    @Test
    void nextEmitClaimsAgainAfterInnerReturns() {
        List<String> claimed = new ArrayList<>();

        NestedAttribution.emit(() -> claimed.add("child"), () -> claimed.add("child-out"));
        NestedAttribution.emit(() -> claimed.add("scheme"), () -> claimed.add("scheme-out"));

        assertEquals(List.of("child", "child-out", "scheme", "scheme-out"), claimed);
    }
}
