package com.agenthub.agent.domain.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台前缀的 Spring 装配契约。所属域：agent。
 * <p>
 * 为什么要有这个测试：类上同时存在「Spring 装配」与「测试手动装配」两个 public 构造器，
 * 一旦漏标 {@code @Autowired}，Spring 既找不到唯一构造器也找不到无参构造器，
 * 会在启动阶段直接抛 {@code No default constructor found} 让整个容器起不来——
 * 这类问题单测不碰容器就发现不了，编译也照样通过。
 * <p>
 * 这里只注册该 Bean 与其配置属性，<b>不连 MySQL / Redis、不监听端口</b>，
 * 仅验证构造器选择与降级行为（无 prompt 域实现时应视为「没有生效 L1」，而不是崩溃）。
 */
class PlatformPromptPrefixWiringTest {

    /** 最小容器装配：只开配置属性绑定，不引入任何基础设施 */
    @Configuration
    @EnableConfigurationProperties(AgentPromptProperties.class)
    static class WiringConfig {
    }

    /**
     * 容器能创建该 Bean（回归：构造器歧义会在这里炸）。
     */
    @Test
    void containerCanCreateBean() {
        try (AnnotationConfigApplicationContext ctx = context()) {
            PlatformPromptPrefix prefix = ctx.getBean(PlatformPromptPrefix.class);
            assertTrue(prefix.isEnabled());
        }
    }

    /**
     * 容器里没有 {@link PlatformConstraintSource} 时必须降级为「无生效 L1」而不是启动失败：
     * ObjectProvider 取不到就当作 null，出站由 AssembledPromptGuard 阻断。
     */
    @Test
    void withoutConstraintSourceBehavesAsNoActiveL1() {
        try (AnnotationConfigApplicationContext ctx = context()) {
            PlatformPromptPrefix prefix = ctx.getBean(PlatformPromptPrefix.class);
            assertEquals("", prefix.textFor("REACT"));
            assertFalse(prefix.startsWithBase("任意提示词"));
        }
    }

    /**
     * 构建只含必要组件的最小容器：一次注册 + 一次 refresh。
     * 注册顺序无关，Spring 会先建配置属性再建依赖它的 Bean。
     *
     * @return 已刷新的容器，调用方负责 close
     */
    private static AnnotationConfigApplicationContext context() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(WiringConfig.class, PlatformPromptPrefix.class);
        ctx.refresh();
        return ctx;
    }
}
