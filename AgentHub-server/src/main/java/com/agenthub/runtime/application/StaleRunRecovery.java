package com.agenthub.runtime.application;

import com.agenthub.runtime.domain.service.CauseDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动与停机时触发遗留运行中会话的收口。所属域：runtime。
 * <p>
 * 启动只扫一次表，失败只打日志，不挡住进程。
 * 停机只收本进程还占着单飞的会话；杀进程来不及走停机钩子时，下次启动再扫。
 */
@Component
public class StaleRunRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StaleRunRecovery.class);

    private final StaleRunCloser closer;

    /**
     * @param closer 条件更新 RUNNING → CANCELLED
     */
    public StaleRunRecovery(StaleRunCloser closer) {
        this.closer = closer;
    }

    /**
     * 容器起来之后扫遗留 RUNNING。重复启动是幂等的：已经不是 RUNNING 的行不会再命中。
     *
     * @param args 启动参数，本次不使用
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            int changed = closer.recoverAfterRestart();
            log.info("启动收口遗留运行中会话 {} 条", changed);
        } catch (RuntimeException ex) {
            log.warn("启动收口遗留运行中会话失败 type={} cause={}",
                    ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
        }
    }

    /**
     * 上下文关闭时收掉还在执行的会话。数据源此时尚未销毁。
     * 钩子失败只记日志，不改变关闭流程。
     *
     * @param event 上下文关闭事件，不读取载荷
     */
    @EventListener(ContextClosedEvent.class)
    public void onShutdown(ContextClosedEvent event) {
        try {
            int changed = closer.recoverOnShutdown();
            log.info("停机收口仍在执行的会话 {} 条", changed);
        } catch (RuntimeException ex) {
            log.warn("停机收口仍在执行的会话失败 type={} cause={}",
                    ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
        }
    }
}
