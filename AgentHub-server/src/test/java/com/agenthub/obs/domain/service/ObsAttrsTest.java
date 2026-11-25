package com.agenthub.obs.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * attrs_json 里的 brief 读写。
 */
class ObsAttrsTest {

    @Test
    void roundTripBrief() {
        String json = ObsAttrs.withBrief(null, "决定调用 queryWeather");
        assertEquals("决定调用 queryWeather", ObsAttrs.briefOf(json));
        assertNull(ObsAttrs.briefOf(null));
        assertNull(ObsAttrs.briefOf("{}"));
    }
}
