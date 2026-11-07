package com.agenthub.runtime.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按空闲时长收口陈旧 RUNNING 并触发会话级 LONG 批抽。所属域：runtime。
 * <p>
 * 这是空闲批抽<b>真实</b>的定时入口（类名就是 {@code TrialSessionCloseJob}）：
 * <ul>
 *   <li>{@code @EnableScheduling} 在 {@code com.agenthub.memory.application.MemoryScheduleConfig} 上，
 *       启动类 {@code com.agenthub.AgentHubApplication} 扫描 {@code com.agenthub} 全包，
 *       因此本类会被注册进 Spring 的调度器，不需要额外配置；</li>
 *   <li>扫描间隔 {@code agenthub.memory.session-close-delay-ms}（默认 60 秒，fixedDelay）；</li>
 *   <li>首轮延迟 {@code agenthub.memory.session-close-initial-delay-ms}（默认 1 小时）。
 *       默认取保守值，避免生产重启后立刻打库 / 出站；本地想尽快看到首轮扫描日志，
 *       用 application-local.yml 或环境变量把该键覆盖为 30000；</li>
 *   <li>是否到期读每个 Agent 策略的 {@code ctx_idle_ttl_sec}（页配，最小 60），不读本类的任何写死值。</li>
 * </ul>
 * 实际逻辑在 {@link TrialSessionCloser}：先 SUCCESS 终态，再幂等批抽；单轮有 N 上限。
 */
@Component
public class TrialSessionCloseJob {

    private static final Logger log = LoggerFactory.getLogger(TrialSessionCloseJob.class);

    private final TrialSessionCloser closer;
    /** 扫描间隔毫秒，仅用于首轮日志对账（真实间隔由 @Scheduled 决定） */
    private final long delayMs;
    /** 首轮延迟毫秒，仅用于首轮日志对账 */
    private final long initialDelayMs;
    /** 只打一次「任务已跑起来」的日志：证明调度注册成功并记下实际生效的首轮延迟 */
    private final AtomicBoolean firstScanLogged = new AtomicBoolean(false);

    /**
     * @param closer         空闲收口 + 批抽（及取消结案）
     * @param delayMs        {@code agenthub.memory.session-close-delay-ms}，默认 60000
     * @param initialDelayMs {@code agenthub.memory.session-close-initial-delay-ms}，默认 3600000
     */
    public TrialSessionCloseJob(TrialSessionCloser closer,
                                @Value("${agenthub.memory.session-close-delay-ms:60000}") long delayMs,
                                @Value("${agenthub.memory.session-close-initial-delay-ms:3600000}") long initialDelayMs) {
        this.closer = closer;
        this.delayMs = delayMs;
        this.initialDelayMs = initialDelayMs;
    }

    /**
     * 用户长时间不聊时，靠这里收口空闲会话并（在开关允许时）抽长期偏好。
     * <p>
     * 首轮延迟独立可配，默认 1 小时（3600000）；扫描间隔默认 60 秒。两者都可被
     * {@code agenthub.memory.session-close-initial-delay-ms} /
     * {@code agenthub.memory.session-close-delay-ms} 覆盖（含环境变量形式）；
     * 本地观测首轮可直接覆盖为 30000，但不改随仓默认值。
     */
    @Scheduled(fixedDelayString = "${agenthub.memory.session-close-delay-ms:60000}",
            initialDelayString = "${agenthub.memory.session-close-initial-delay-ms:3600000}")
    public void sweep() {
        if (firstScanLogged.compareAndSet(false, true)) {
            // 只需一次 INFO：确认「定时任务确实注册并开始跑了」，之后每轮不再刷屏
            log.info("空闲批抽定时任务已开始首轮扫描 initialDelayMs={} fixedDelayMs={}",
                    initialDelayMs, delayMs);
        }
        closer.sweep();
    }
}
