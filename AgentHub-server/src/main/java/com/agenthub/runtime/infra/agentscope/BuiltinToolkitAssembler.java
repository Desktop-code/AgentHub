package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.model.BuiltinToolSpec;
import com.agenthub.runtime.domain.service.CauseDigest;
import com.agenthub.runtime.domain.service.LimitHitReasons;
import com.agenthub.runtime.domain.service.TrialFailure;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把绑定且启用的 BUILTIN 注册进 AgentScope Toolkit。所属域：runtime 的 infra。
 * 只接受带同名 {@code @Tool} 的 Spring Bean。没有实现就失败，不注册占位工具。
 * 模型看到的 parameters 用 Java 注解重写，不读 tool_item.input_schema_json。
 */
@Component
public class BuiltinToolkitAssembler {

    private static final Logger log = LoggerFactory.getLogger(BuiltinToolkitAssembler.class);

    private final BuiltinToolBeans beans;

    /**
     * @param beans 按 tool_item.name 查找的实现索引
     */
    public BuiltinToolkitAssembler(BuiltinToolBeans beans) {
        this.beans = beans;
    }

    /**
     * 按目录调用名注册。同一个 Bean 只注册一次，但每个名字都必须能在 Toolkit 里查到。
     *
     * @param specs 已过滤为绑定且启用的 BUILTIN；可以是空
     * @return 只含这些实现的工具箱
     * @throws TrialFailure 某个调用名没有对应 Bean，或注册后引擎仍看不到该工具
     */
    public Toolkit assemble(List<BuiltinToolSpec> specs) {
        Toolkit toolkit = new Toolkit();
        if (specs == null || specs.isEmpty()) {
            return toolkit;
        }
        Set<Object> registered = Collections.newSetFromMap(new IdentityHashMap<>());
        for (BuiltinToolSpec spec : specs) {
            String name = spec == null || spec.getName() == null ? "" : spec.getName().trim();
            Object bean = beans.require(name);
            if (registered.add(bean)) {
                try {
                    toolkit.registerTool(bean);
                } catch (RuntimeException ex) {
                    // 注册失败会让整轮直接起不来：带栈打，才能看出是重名、schema 非法还是引擎反射问题
                    log.warn("注册内置工具失败 name={} type={} cause={}",
                            name, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
                    throw new TrialFailure("内置工具「" + name + "」注册失败", LimitHitReasons.ERROR, ex);
                }
            }
            if (toolkit.getTool(name) == null) {
                throw new TrialFailure("内置工具「" + name + "」注册后不可调用");
            }
            // 引擎默认扫描没有 minimum/maximum/default；用注解生成结果盖住，库表空或旧都不影响模型约束
            overlayJavaSchema(toolkit, bean, name);
        }
        return toolkit;
    }

    /**
     * 生成失败时留下引擎自己的 schema，避免参数描述问题把整次试跑打掉。
     *
     * @param toolkit 已经注册过该调用名
     * @param bean    带 {@code @Tool} 的实现
     * @param name    调用名
     */
    private void overlayJavaSchema(Toolkit toolkit, Object bean, String name) {
        Map<String, Object> parameters;
        try {
            parameters = BuiltinToolSchemas.parametersOf(bean, name);
        } catch (RuntimeException ex) {
            log.warn("内置工具 schema 生成失败，沿用引擎扫描结果 name={} type={}", name, ex.getClass().getSimpleName());
            return;
        }
        if (parameters == null) {
            return;
        }
        AgentTool current = toolkit.getTool(name);
        if (current == null || current instanceof JavaSchemaAgentTool) {
            return;
        }
        toolkit.registerAgentTool(new JavaSchemaAgentTool(current, parameters));
    }
}
