package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.model.BuiltinToolSpec;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.infra.agentscope.builtin.BrowserTool;
import com.agenthub.runtime.infra.agentscope.builtin.SystemInfoTool;
import com.agenthub.runtime.infra.agentscope.builtin.WeatherQueryTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 绑定的 BUILTIN 必须注册成真实 @Tool Bean。找不到实现时失败，不能返回占位文案。
 */
class BuiltinToolkitAssemblerTest {

    /**
     * 三个内置实现的 @Tool(name) 与种子里的 tool_item.name 一致，并进入 Toolkit。
     */
    @Test
    void registersBeansWhoseToolNameMatchesCatalog() {
        WeatherQueryTool weather = new WeatherQueryTool(new RestTemplate(), new ObjectMapper());
        SystemInfoTool system = new SystemInfoTool(new ObjectMapper());
        BrowserTool browser = new BrowserTool(Duration.ofSeconds(1));
        BuiltinToolkitAssembler assembler = assembler(weather, system, browser);

        Toolkit toolkit = assembler.assemble(List.of(spec("queryWeather"), spec("systemInfo"), spec("browserUse")));

        assertNotNull(toolkit.getTool("queryWeather"));
        assertNotNull(toolkit.getTool("systemInfo"));
        assertNotNull(toolkit.getTool("browserUse"));
    }

    /**
     * 目录名没有对应 Bean 时，失败文案不能再写成「只有目录元数据」。
     */
    @Test
    void failsWhenNoBeanMatchesToolName() {
        BuiltinToolkitAssembler assembler = assembler(new WeatherQueryTool(new RestTemplate(), new ObjectMapper()));

        TrialFailure failure = assertThrows(TrialFailure.class,
                () -> assembler.assemble(List.of(spec("missingTool"))));

        assertTrue(failure.getMessage().contains("missingTool"));
        assertFalse(failure.getMessage().contains("只有目录元数据"));
    }

    /**
     * 同一个调用名落在两个 Bean 上时不能挑一个注册。
     */
    @Test
    void failsWhenTwoBeansShareTheSameToolName() {
        BuiltinToolkitAssembler assembler = assembler(new Left(), new Right());

        TrialFailure failure = assertThrows(TrialFailure.class,
                () -> assembler.assemble(List.of(spec("queryWeather"))));

        assertTrue(failure.getMessage().contains("多个实现"));
    }

    private static BuiltinToolkitAssembler assembler(Object... beans) {
        BuiltinToolBeans.Indexed indexed = BuiltinToolBeans.index(List.of(beans));
        return new BuiltinToolkitAssembler(new BuiltinToolBeans(indexed));
    }

    private static BuiltinToolSpec spec(String name) {
        BuiltinToolSpec spec = new BuiltinToolSpec();
        spec.setName(name);
        return spec;
    }

    /** 与另一个桩抢同一个调用名，用来覆盖重名失败。 */
    public static class Left {
        @Tool(name = "queryWeather", description = "left")
        public String queryWeather(@ToolParam(name = "city", description = "城市") String city) {
            return city;
        }
    }

    /** 与 Left 同名，索引必须记成冲突。 */
    public static class Right {
        @Tool(name = "queryWeather", description = "right")
        public String queryWeather(@ToolParam(name = "city", description = "城市") String city) {
            return city;
        }
    }
}
