package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.model.BuiltinToolSpec;
import com.agenthub.runtime.domain.service.BuiltinSchemaSource;
import com.agenthub.runtime.infra.agentscope.builtin.BrowserTool;
import com.agenthub.runtime.infra.agentscope.builtin.SystemInfoTool;
import com.agenthub.runtime.infra.agentscope.builtin.WeatherQueryTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java 注解生成的 BUILTIN schema：queryWeather 的 city 必填、days 可选；库表空或旧文不削弱模型侧约束。
 */
class BuiltinToolSchemaTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String QUERY_WEATHER =
            "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\",\"description\":\"城市名称，支持中文或英文，例如北京、上海、Hangzhou\"},\"days\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":7,\"default\":3,\"description\":\"预报天数：从含今天起共 N 个自然日，1～7，默认 3；问明天+后天应传 days=3\"}},\"required\":[\"city\"]}";

    private static final String SYSTEM_INFO = "{\"type\":\"object\",\"properties\":{}}";

    private static final String BROWSER_USE =
            "{\"type\":\"object\",\"properties\":{"
                    + "\"action\":{\"type\":\"string\",\"description\":\"动作：open 打开网址，fill 按选择器填值，click 点击，screenshot 截图，read 读取标题和可见文本，close 关闭会话。不传时：给了 url 就打开，否则读取当前页\"},"
                    + "\"url\":{\"type\":\"string\",\"description\":\"open 时要打开的公网网址\"},"
                    + "\"selector\":{\"type\":\"string\",\"description\":\"CSS 选择器，fill 和 click 使用\"},"
                    + "\"value\":{\"type\":\"string\",\"description\":\"fill 时写入的文本\"}}}";

    /**
     * 元数据生成结果须带 city required、days 可选，以及 1～7 和默认 3。
     */
    @Test
    void queryWeatherSchemaRequiresCityAndLeavesDaysOptional() throws Exception {
        String schema = BuiltinToolSchemas.jsonOf(WeatherQueryTool.class, "queryWeather");
        assertEquals(QUERY_WEATHER, schema);
        JsonNode node = JSON.readTree(schema);
        assertEquals(1, node.get("required").size());
        assertEquals("city", node.get("required").get(0).asText());
        assertFalse(node.get("required").toString().contains("days"));
        JsonNode days = node.get("properties").get("days");
        assertEquals("integer", days.get("type").asText());
        assertEquals(1, days.get("minimum").asInt());
        assertEquals(7, days.get("maximum").asInt());
        assertEquals(3, days.get("default").asInt());
        assertTrue(days.get("description").asText().contains("含今天起共 N"));
    }

    @Test
    void systemInfoSchemaHasEmptyProperties() {
        assertEquals(SYSTEM_INFO, BuiltinToolSchemas.jsonOf(SystemInfoTool.class, "systemInfo"));
    }

    /**
     * 填表和点击不必带网址，所以 url 不再是整份 schema 的必填。
     * 四个动作参数都在，工具说明与目录 description 同一句。
     */
    @Test
    void browserUseSchemaExposesSessionActionsWithoutRequiringUrl() throws Exception {
        String schema = BuiltinToolSchemas.jsonOf(BrowserTool.class, "browserUse");
        assertEquals(BROWSER_USE, schema);
        JsonNode node = JSON.readTree(schema);
        assertTrue(node.get("required") == null || node.get("required").isNull());
        assertTrue(node.get("properties").has("action"));
        assertTrue(node.get("properties").has("url"));
        assertTrue(BrowserTool.DESCRIPTION.contains("不能自动识别验证码"));
    }

    /**
     * 库表为 NULL 时，注册进 Toolkit 的 parameters 仍是完整 Java schema，模型不会只看到占位名。
     */
    @Test
    void nullDbSchemaStillExposesFullParametersOnToolkit() {
        String resolved = BuiltinSchemaSource.prefer(null, BuiltinToolSchemas.jsonOf(WeatherQueryTool.class, "queryWeather"));
        assertEquals(QUERY_WEATHER, resolved);

        WeatherQueryTool weather = new WeatherQueryTool(new RestTemplate(), new ObjectMapper());
        SystemInfoTool system = new SystemInfoTool(new ObjectMapper());
        BrowserTool browser = new BrowserTool(Duration.ofSeconds(1));
        BuiltinToolkitAssembler assembler = new BuiltinToolkitAssembler(
                new BuiltinToolBeans(BuiltinToolBeans.index(List.of(weather, system, browser))));
        Toolkit toolkit = assembler.assemble(List.of(spec("queryWeather"), spec("systemInfo"), spec("browserUse")));

        Map<String, Object> weatherParams = parameters(toolkit, "queryWeather");
        assertEquals(List.of("city"), weatherParams.get("required"));
        assertFalse(((List<?>) weatherParams.get("required")).contains("days"));
        @SuppressWarnings("unchecked")
        Map<String, Object> days = (Map<String, Object>) ((Map<?, ?>) weatherParams.get("properties")).get("days");
        assertEquals(1, ((Number) days.get("minimum")).intValue());
        assertEquals(7, ((Number) days.get("maximum")).intValue());
        assertEquals(3, ((Number) days.get("default")).intValue());

        Map<String, Object> systemParams = parameters(toolkit, "systemInfo");
        assertTrue(((Map<?, ?>) systemParams.get("properties")).isEmpty());
        assertFalse(systemParams.containsKey("required"));

        Map<String, Object> browserParams = parameters(toolkit, "browserUse");
        assertFalse(browserParams.containsKey("required"));
        assertTrue(((Map<?, ?>) browserParams.get("properties")).containsKey("url"));
        assertTrue(((Map<?, ?>) browserParams.get("properties")).containsKey("selector"));
    }

    /**
     * 库里残留的空对象不能盖过 Java 约束。
     */
    @Test
    void staleDbSchemaDoesNotWeakenJavaConstraints() {
        String stale = "{\"type\":\"object\",\"properties\":{}}";
        String resolved = BuiltinSchemaSource.prefer(stale,
                BuiltinToolSchemas.jsonOf(WeatherQueryTool.class, "queryWeather"));
        assertEquals(QUERY_WEATHER, resolved);
    }

    /**
     * Java 侧没有该工具时仍把库表原文（含 null）交回去，空 schema 占位逻辑继续生效。
     */
    @Test
    void missingJavaSchemaFallsBackToDbIncludingNull() {
        assertNull(BuiltinSchemaSource.prefer(null, null));
        assertNull(BuiltinSchemaSource.prefer(null, "  "));
        assertEquals("{\"a\":1}", BuiltinSchemaSource.prefer("{\"a\":1}", null));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parameters(Toolkit toolkit, String name) {
        ToolSchema schema = toolkit.getToolSchemas().stream()
                .filter(one -> name.equals(one.getName()))
                .findFirst()
                .orElseThrow();
        return schema.getParameters();
    }

    private static BuiltinToolSpec spec(String name) {
        BuiltinToolSpec spec = new BuiltinToolSpec();
        spec.setName(name);
        return spec;
    }
}
