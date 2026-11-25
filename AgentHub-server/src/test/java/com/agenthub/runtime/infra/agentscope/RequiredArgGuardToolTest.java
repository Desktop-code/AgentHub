package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.model.BuiltinToolSpec;
import com.agenthub.runtime.domain.model.MemoryFact;
import com.agenthub.runtime.domain.service.RequiredToolArgs;
import com.agenthub.runtime.infra.agentscope.builtin.WeatherQueryTool;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 必填参数闸门接进 Toolkit 后的真实行为：无依据拦截时原工具零调用，记忆补参时用补全后的值出站。
 * 所属域：runtime 的 infra 单测。用桩工具统计调用次数，证明「不出站」。
 */
class RequiredArgGuardToolTest {

    private static final Function<String, Optional<String>> CANONICAL = raw -> {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String key = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (key) {
            case "city", "residence", "residence_city", "居住城市" -> Optional.of("residence_city");
            default -> Optional.of(key);
        };
    };

    /**
     * 无记忆 + 模型带了臆造 city：工具一次都不执行，返回 ERROR + 追问文案。
     */
    @Test
    void fabricatedCityNeverInvokesDelegate() {
        CountingTool delegate = new CountingTool(Map.of(
                "type", "object",
                "properties", Map.of("city", Map.of("type", "string")),
                "required", List.of("city")));
        Toolkit toolkit = wrap(delegate, RequiredToolArgs.Evidence.empty());

        ToolResultBlock result = call(toolkit, "queryWeather", Map.of("city", "永平")).block();

        assertNotNull(result);
        assertEquals(ToolResultState.ERROR, result.getState());
        assertEquals(0, delegate.calls.get(), "拦截后不得触达原工具（即不出站）");
        String text = result.getOutput().toString();
        assertTrue(text.contains("未执行"));
        assertTrue(text.contains("追问"));
    }

    /**
     * 缺 city 且无记忆：同样零调用。
     */
    @Test
    void missingCityNeverInvokesDelegate() {
        CountingTool delegate = new CountingTool(Map.of("required", List.of("city")));
        Toolkit toolkit = wrap(delegate, RequiredToolArgs.Evidence.empty());

        ToolResultBlock result = call(toolkit, "queryWeather", Map.of()).block();

        assertEquals(ToolResultState.ERROR, result.getState());
        assertEquals(0, delegate.calls.get());
    }

    /**
     * 已注入居住城市 LONG：闸门补参后放行，原工具收到的 city 是记忆值。
     */
    @Test
    void injectedMemoryFillsCityThenInvokesDelegate() {
        CountingTool delegate = new CountingTool(Map.of("required", List.of("city")));
        Toolkit toolkit = wrap(delegate, new RequiredToolArgs.Evidence("今天天气如何？", "今天天气如何？",
                List.of(new MemoryFact("residence_city", "居住城市", "杭州"))));

        ToolResultBlock result = call(toolkit, "queryWeather", Map.of()).block();

        assertNotNull(result);
        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertEquals(1, delegate.calls.get());
        assertEquals("杭州", delegate.lastInput.get("city"), "补参值必须真的传给原工具");
    }

    /**
     * 用户口头给了城市：原样出站，入参不被改写。
     */
    @Test
    void userProvidedCityInvokesDelegateUnchanged() {
        CountingTool delegate = new CountingTool(Map.of("required", List.of("city")));
        Toolkit toolkit = wrap(delegate, new RequiredToolArgs.Evidence("上海天气", "上海天气", List.of()));

        ToolResultBlock result = call(toolkit, "queryWeather", Map.of("city", "上海")).block();

        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertEquals(1, delegate.calls.get());
        assertEquals("上海", delegate.lastInput.get("city"));
    }

    /**
     * 连续被拦时升级为「不要再调工具」，避免模型反复换假值空转。
     */
    @Test
    void repeatedBlocksEscalateToAskingUser() {
        CountingTool delegate = new CountingTool(Map.of("required", List.of("city")));
        Toolkit toolkit = wrap(delegate, RequiredToolArgs.Evidence.empty());

        call(toolkit, "queryWeather", Map.of("city", "永平")).block();
        ToolResultBlock second = call(toolkit, "queryWeather", Map.of("city", "永平")).block();

        assertEquals(0, delegate.calls.get());
        assertEquals(ToolResultState.ERROR, second.getState());
        assertTrue(second.getOutput().toString().contains("连续"));
    }

    /**
     * 模型侧 schema 去掉 required（避免引擎抢先驳回、诱导模型换值），但仍用「（必填）」标注参数含义；
     * 校验判据保留在原 required 上，缺参仍会被拦住。
     */
    @Test
    void guardKeepsRequiredForCheckButMarksSchemaAsRequiredText() {
        CountingTool delegate = new CountingTool(Map.of(
                "type", "object",
                "properties", Map.of("city", Map.of("type", "string", "description", "城市名称")),
                "required", List.of("city")));
        Toolkit toolkit = wrap(delegate, RequiredToolArgs.Evidence.empty());

        AgentTool guarded = toolkit.getTool("queryWeather");
        assertNotNull(guarded);
        Map<String, Object> exposed = guarded.getParameters();
        assertFalse(exposed.containsKey("required"), "模型侧不应再带 required，否则会被引擎通用驳回");
        @SuppressWarnings("unchecked")
        Map<String, Object> city = (Map<String, Object>) ((Map<?, ?>) exposed.get("properties")).get("city");
        assertEquals("string", city.get("type"));
        assertTrue(city.get("description").toString().contains("必填"), "仍要让模型知道这是必填");
    }

    /**
     * 真实内置工具也走同一闸门：schema 来自 Java 注解，required 被转成「必填」标注。
     */
    @Test
    void weatherToolSchemaPassesThroughGuard() {
        WeatherQueryTool weather = new WeatherQueryTool(new RestTemplate(), new com.fasterxml.jackson.databind.ObjectMapper());
        BuiltinToolkitAssembler assembler = new BuiltinToolkitAssembler(
                new BuiltinToolBeans(BuiltinToolBeans.index(List.of(weather))));
        BuiltinToolSpec spec = new BuiltinToolSpec();
        spec.setName("queryWeather");
        Toolkit toolkit = assembler.assemble(List.of(spec));
        ToolkitRequiredArgs.wrap(toolkit, RequiredToolArgs.Evidence.empty(),
                RequiredToolArgs.Rules.defaults(), CANONICAL, new RequiredArgBlocks(), true);

        AgentTool guarded = toolkit.getTool("queryWeather");
        assertNotNull(guarded);
        Map<String, Object> exposed = guarded.getParameters();
        assertFalse(exposed.containsKey("required"));
        // 原 schema 的 required 仍在原工具上，闸门据此判断；模型侧只看到「必填」文案
        assertEquals(List.of("city"), RequiredToolArgs.requiredOf(
                assembler.assemble(List.of(spec)).getTool("queryWeather").getParameters()));
        @SuppressWarnings("unchecked")
        Map<String, Object> city = (Map<String, Object>) ((Map<?, ?>) exposed.get("properties")).get("city");
        assertTrue(city.get("description").toString().contains("必填"));
        @SuppressWarnings("unchecked")
        Map<String, Object> days = (Map<String, Object>) ((Map<?, ?>) exposed.get("properties")).get("days");
        assertFalse(days.get("description").toString().contains("必填"), "days 是可选参数，不得标成必填");
    }

    private static Toolkit wrap(AgentTool delegate, RequiredToolArgs.Evidence evidence) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(delegate);
        return ToolkitRequiredArgs.wrap(toolkit, evidence, RequiredToolArgs.Rules.defaults(), CANONICAL,
                new RequiredArgBlocks(), true);
    }

    private static reactor.core.publisher.Mono<ToolResultBlock> call(Toolkit toolkit, String name,
                                                                    Map<String, Object> input) {
        ToolUseBlock use = ToolUseBlock.builder().id("call-1").name(name).input(input).build();
        ToolCallParam param = ToolCallParam.builder().toolUseBlock(use).input(input).build();
        return toolkit.getTool(name).callAsync(param);
    }

    /**
     * 桩工具：记录调用次数与最后一次入参，用来证明是否真正出站。
     */
    private static final class CountingTool implements AgentTool {

        private final Map<String, Object> parameters;
        private final AtomicInteger calls = new AtomicInteger();
        private Map<String, Object> lastInput = Map.of();

        private CountingTool(Map<String, Object> parameters) {
            this.parameters = parameters;
        }

        @Override
        public String getName() {
            return "queryWeather";
        }

        @Override
        public String getDescription() {
            return "桩工具";
        }

        @Override
        public Map<String, Object> getParameters() {
            return parameters;
        }

        @Override
        public reactor.core.publisher.Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            calls.incrementAndGet();
            lastInput = param.getInput();
            // 显式 SUCCESS，与引擎返回的真实工具状态保持一致
            return reactor.core.publisher.Mono.just(ToolResultBlock.builder()
                    .output(io.agentscope.core.message.TextBlock.builder().text("ok").build())
                    .state(ToolResultState.SUCCESS)
                    .build());
        }
    }
}
