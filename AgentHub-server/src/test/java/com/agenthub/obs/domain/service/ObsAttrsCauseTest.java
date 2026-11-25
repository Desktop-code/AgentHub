package com.agenthub.obs.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * attrs_json 里的 brief / cause 读写。所属域：obs。
 */
class ObsAttrsCauseTest {

    @Test
    void causeCoexistsWithBrief() {
        String attrs = ObsAttrs.withBrief(null, "图片文字识别失败");
        attrs = ObsAttrs.withCause(attrs, "IOException: connect timed out <- ModelException at com.agenthub.X.y");
        assertEquals("图片文字识别失败", ObsAttrs.briefOf(attrs));
        String cause = ObsAttrs.causeOf(attrs);
        assertTrue(cause != null && cause.contains("connect timed out"), String.valueOf(cause));
        // cause 单独存在时 brief 仍可读，二者互不覆盖
        String onlyCause = ObsAttrs.withCause(null, "IllegalStateException: x");
        assertNull(ObsAttrs.briefOf(onlyCause));
        assertEquals("IllegalStateException: x", ObsAttrs.causeOf(onlyCause));
    }

    @Test
    void injectionDoesNotDropCause() {
        String attrs = ObsAttrs.withCause(null, "TimeoutException: 60s");
        attrs = ObsAttrs.withInjection(attrs, 3, 1, 900, false);
        assertTrue(attrs.contains("\"cause\""));
        assertTrue(attrs.contains("tools_injected"));
        assertEquals("TimeoutException: 60s", ObsAttrs.causeOf(attrs));
    }

    @Test
    void blankCauseIsNoop() {
        assertNull(ObsAttrs.withCause(null, null));
        assertNull(ObsAttrs.withCause(null, "  "));
        assertEquals("{\"brief\":\"ok\"}", ObsAttrs.withCause("{\"brief\":\"ok\"}", ""));
    }
}
