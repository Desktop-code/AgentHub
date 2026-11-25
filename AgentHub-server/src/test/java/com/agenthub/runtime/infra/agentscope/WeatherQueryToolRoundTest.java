package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.model.BuiltinToolSpec;
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.TrialTrace;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.service.TextToolCallGuard;
import com.agenthub.runtime.infra.agentscope.builtin.WeatherQueryTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * queryWeather 真调用（桩 HTTP）可出结果，注册进 Toolkit，并构成可收束最终答。
 */
class WeatherQueryToolRoundTest {

    @Test
    void queryWeatherExecutesAndFinalAnswerPassesGuard() {
        ObjectMapper mapper = new ObjectMapper();
        RestTemplate rest = new RestTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T getForObject(URI url, Class<T> responseType) {
                String host = url.getHost() == null ? "" : url.getHost();
                if (host.contains("geocoding")) {
                    ObjectNode place = mapper.createObjectNode();
                    place.put("name", "北京");
                    place.put("admin1", "北京市");
                    place.put("country", "中国");
                    place.put("latitude", 39.9);
                    place.put("longitude", 116.4);
                    ArrayNode results = mapper.createArrayNode();
                    results.add(place);
                    ObjectNode root = mapper.createObjectNode();
                    root.set("results", results);
                    return (T) root;
                }
                ObjectNode root = mapper.createObjectNode();
                root.put("timezone", "Asia/Shanghai");
                ObjectNode current = root.putObject("current");
                current.put("time", "2026-10-27T12:00");
                current.put("temperature_2m", 22.0);
                current.put("apparent_temperature", 21.0);
                current.put("relative_humidity_2m", 40);
                current.put("weather_code", 0);
                current.put("wind_speed_10m", 5.0);
                ObjectNode daily = root.putObject("daily");
                daily.putArray("time").add("2026-10-27");
                daily.putArray("weather_code").add(0);
                daily.putArray("temperature_2m_max").add(25.0);
                daily.putArray("temperature_2m_min").add(15.0);
                daily.putArray("precipitation_probability_max").add(10);
                return (T) root;
            }
        };

        WeatherQueryTool weather = new WeatherQueryTool(rest, mapper);
        String raw = weather.queryWeather("北京", 1);
        assertTrue(raw.contains("北京"));
        assertTrue(raw.contains("22"));
        // 播报回灌不带纯地理技术字段；预报行仍够写播报
        assertFalse(raw.contains("\"latitude\""));
        assertFalse(raw.contains("\"longitude\""));
        assertFalse(raw.contains("\"country\""));
        assertTrue(raw.contains("\"forecast\""));
        assertTrue(raw.contains("\"maxC\""));
        assertTrue(raw.contains("\"minC\""));
        assertTrue(raw.contains("\"precipProbPercent\""));

        BuiltinToolSpec spec = new BuiltinToolSpec();
        spec.setName("queryWeather");
        BuiltinToolkitAssembler assembler = new BuiltinToolkitAssembler(
                new BuiltinToolBeans(BuiltinToolBeans.index(List.of(weather))));
        Toolkit toolkit = assembler.assemble(List.of(spec));
        assertNotNull(toolkit.getTool("queryWeather"));

        TrialTurn turn = new TrialTurn();
        TrialTrace tool = new TrialTrace();
        tool.setType(EventType.TOOL);
        tool.setName("queryWeather");
        tool.setSuccess(true);
        tool.setArguments("{\"city\":\"北京\"}");
        tool.setContent(raw);
        turn.setTraces(List.of(tool));
        turn.setAssistantText("北京今天晴，气温约 22°C。");
        assertDoesNotThrow(() -> TextToolCallGuard.assertNoOrphanTextToolCall(turn));
    }
}
