package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.service.TrialFailure;
import io.agentscope.core.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 按 {@code @Tool(name)} 索引内置工具 Bean。所属域：runtime 的 infra。
 * 试跑只注册绑定且启用的目录项；这里只负责把调用名对上 Spring Bean。
 * 索引放在单例创建完成之后，避免构造期互相取 Bean。
 */
@Component
public class BuiltinToolBeans implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(BuiltinToolBeans.class);

    private final ApplicationContext context;
    private Map<String, Object> byName = Map.of();
    private Map<String, String> conflicts = Map.of();

    /**
     * @param context 用来枚举本应用里带 @Tool 的单例
     */
    @Autowired
    public BuiltinToolBeans(ApplicationContext context) {
        this.context = context;
    }

    /**
     * 测试时直接放入已扫好的索引，不再读取容器。
     *
     * @param indexed 调用名到 Bean，以及重名冲突
     */
    BuiltinToolBeans(Indexed indexed) {
        this.context = null;
        Indexed ready = indexed == null ? index(List.of()) : indexed;
        this.byName = ready.byName;
        this.conflicts = ready.conflicts;
    }

    /**
     * 容器里的工具 Bean 都已创建后再扫描，保证能拿到注入完成的实例。
     */
    @Override
    public void afterSingletonsInstantiated() {
        List<Object> candidates = new ArrayList<>();
        for (String beanName : context.getBeanDefinitionNames()) {
            Object bean = candidate(beanName);
            if (bean != null) {
                candidates.add(bean);
            }
        }
        Indexed indexed = index(candidates);
        this.byName = indexed.byName;
        this.conflicts = indexed.conflicts;
        log.info("已索引内置工具 {} 个", byName.size());
    }

    /**
     * 按调用名查找实现。给 schema 生成和回填用，缺失或重名时 empty，不抛试跑失败。
     *
     * @param toolName tool_item.name
     * @return 可反射的 Bean；没有或冲突时 empty
     */
    public Optional<Object> find(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        String key = toolName.trim();
        if (conflicts.containsKey(key)) {
            return Optional.empty();
        }
        return Optional.ofNullable(byName.get(key));
    }

    /**
     * 按目录里的调用名取实现。重名或缺失都视为装配失败，不能挑一个凑合。
     *
     * @param toolName tool_item.name，已去掉首尾空白
     * @return 交给 {@code toolkit.registerTool} 的对象
     * @throws TrialFailure 调用名为空、没有实现，或同一个名字有多个实现
     */
    public Object require(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            throw new TrialFailure("内置工具调用名为空，无法匹配实现");
        }
        String key = toolName.trim();
        if (conflicts.containsKey(key)) {
            log.warn("内置工具重名 name={} beans={}", key, conflicts.get(key));
            throw new TrialFailure("内置工具「" + key + "」匹配到多个实现");
        }
        Object bean = byName.get(key);
        if (bean == null) {
            throw new TrialFailure("内置工具「" + key + "」没有 @Tool(name) 与之相同的实现");
        }
        return bean;
    }

    /**
     * 把一组对象上的 @Tool 方法收成调用名索引。同一个名字出现在不同对象上记为冲突。
     *
     * @param candidates Spring Bean 或测试桩
     * @return 可注册对象与冲突说明
     */
    static Indexed index(Iterable<Object> candidates) {
        Map<String, Object> found = new LinkedHashMap<>();
        Map<String, String> clash = new LinkedHashMap<>();
        if (candidates == null) {
            return new Indexed(found, clash);
        }
        for (Object bean : candidates) {
            if (bean == null) {
                continue;
            }
            Class<?> userClass = ClassUtils.getUserClass(bean);
            Object registerable = registerable(bean);
            for (Method method : toolMethods(userClass)) {
                String name = toolName(method);
                if (clash.containsKey(name)) {
                    continue;
                }
                Object previous = found.get(name);
                if (previous == null) {
                    found.put(name, registerable);
                    continue;
                }
                if (previous != registerable) {
                    clash.put(name, previous.getClass().getName() + "," + registerable.getClass().getName());
                    found.remove(name);
                }
            }
        }
        return new Indexed(found, clash);
    }

    private Object candidate(String beanName) {
        if (!context.isSingleton(beanName)) {
            return null;
        }
        Class<?> type;
        try {
            type = context.getType(beanName);
        } catch (RuntimeException ex) {
            return null;
        }
        if (type == null) {
            return null;
        }
        Class<?> userClass = ClassUtils.getUserClass(type);
        if (!userClass.getName().startsWith("com.agenthub.") || toolMethods(userClass).isEmpty()) {
            return null;
        }
        try {
            return context.getBean(beanName);
        } catch (RuntimeException ex) {
            log.warn("读取内置工具 Bean 失败 name={} type={}", beanName, ex.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * AgentScope 只扫描运行时类自己声明的方法，父类上的 @Tool 它看不到。
     * 代理类本身没有注解，这时改注册代理后面的目标对象。
     */
    private static Object registerable(Object bean) {
        if (declaresTool(bean.getClass())) {
            return bean;
        }
        Object target = AopProxyUtils.getSingletonTarget(bean);
        if (target != null && declaresTool(target.getClass())) {
            return target;
        }
        return bean;
    }

    /** 只看本类声明，不把父类上的 @Tool 算成代理类自己的方法。 */
    private static boolean declaresTool(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getAnnotation(Tool.class) != null) {
                return true;
            }
        }
        return false;
    }

    private static List<Method> toolMethods(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getAnnotation(Tool.class) != null) {
                    methods.add(method);
                }
            }
        }
        return methods;
    }

    private static String toolName(Method method) {
        Tool annotation = method.getAnnotation(Tool.class);
        if (annotation == null || annotation.name() == null || annotation.name().isBlank()) {
            return method.getName();
        }
        return annotation.name();
    }

    /**
     * 一次扫描的结果。冲突项不会留在 byName 里，避免试跑悄悄用其中一个。
     */
    static final class Indexed {
        private final Map<String, Object> byName;
        private final Map<String, String> conflicts;

        private Indexed(Map<String, Object> byName, Map<String, String> conflicts) {
            this.byName = Map.copyOf(byName);
            this.conflicts = Map.copyOf(conflicts);
        }
    }
}
