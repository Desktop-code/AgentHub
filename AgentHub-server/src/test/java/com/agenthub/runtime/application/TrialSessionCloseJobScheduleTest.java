package com.agenthub.runtime.application;

import com.agenthub.memory.application.MemoryScheduleConfig;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空闲批抽的调度契约。所属域：runtime。
 * <p>
 * 目的：防止「定时任务被改名/删注解/默认值被悄悄改掉」这类改动悄悄让手测失效——
 * 单测里断言真实类名、扫描间隔与首轮延迟的默认值，以及 @EnableScheduling 仍在扫描范围内。
 */
class TrialSessionCloseJobScheduleTest {

    /** 真实入口类必须带 @Component，否则不会被 Spring 注册，@Scheduled 永远不触发。 */
    @Test
    void jobIsAComponent() {
        assertNotNull(TrialSessionCloseJob.class.getAnnotation(Component.class));
    }

    /** 扫描间隔默认 60 秒、首轮延迟默认 1 小时；两者都保留可配置占位符。 */
    @Test
    void sweepScheduleDefaults() throws NoSuchMethodException {
        Method sweep = TrialSessionCloseJob.class.getMethod("sweep");
        Scheduled scheduled = sweep.getAnnotation(Scheduled.class);
        assertNotNull(scheduled, "sweep 必须保留 @Scheduled");
        assertEquals("${agenthub.memory.session-close-delay-ms:60000}", scheduled.fixedDelayString());
        // 随仓默认保持 1 小时；本地观测用配置覆盖成 30000，不改默认值
        assertEquals("${agenthub.memory.session-close-initial-delay-ms:3600000}", scheduled.initialDelayString());
    }

    /** 调度能力来自 memory 域配置类的 @EnableScheduling；删除它会让所有 @Scheduled 静默失效。 */
    @Test
    void schedulingIsEnabled() {
        assertTrue(MemoryScheduleConfig.class.isAnnotationPresent(EnableScheduling.class),
                "@EnableScheduling 必须在组件扫描范围内保留一个");
    }
}
