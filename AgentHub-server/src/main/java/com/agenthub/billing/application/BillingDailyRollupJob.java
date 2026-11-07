package com.agenthub.billing.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 当日 Token 日汇总定时任务。所属域：billing。
 * <p>
 * 每小时的 10 / 30 / 50 分（Asia/Shanghai）把「今天」的正常流水聚合成日汇总：
 * 同维新增或覆盖，无流水旧行清零；与控制台「重算」共用 {@link BillingDailyAppService#rebuildRange}。
 * <p>
 * 边界与闸门：
 * <ul>
 *   <li>开关 {@code agenthub.billing.daily-rollup-enabled} 关闭时立即返回，不查库；</li>
 *   <li>本机并发：上一轮未结束则 skip，避免同一 JVM 叠跑；</li>
 *   <li>幂等：重复触发只覆盖同一套维度总量，不依赖「只成功一次」的外部锁；</li>
 *   <li>异常吞掉并打 warn：定时失败不拖垮调度线程，下一拍自然再跑；</li>
 *   <li>只汇总当日，不扫历史（历史靠手动重算）；不出站、不调模型。</li>
 * </ul>
 * {@code @EnableScheduling} 已在 memory 的 {@code MemoryScheduleConfig} 开启，本类随 {@code com.agenthub} 扫描注册即可。
 */
@Component
public class BillingDailyRollupJob {

    private static final Logger log = LoggerFactory.getLogger(BillingDailyRollupJob.class);

    /** 业务日与 cron 对齐的时区；与 jackson / 库连接串一致 */
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    private final BillingDailyAppService daily;
    private final boolean enabled;
    private final long operatorId;
    /** 同 JVM 防叠跑；多实例各自跑一轮，落库仍按同维覆盖收敛 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * @param daily      日汇总编排（与手动重算同一 bean）
     * @param enabled    总闸，默认开
     * @param operatorId 写入审计字段的系统占位用户 id，默认 0
     */
    public BillingDailyRollupJob(
            BillingDailyAppService daily,
            @Value("${agenthub.billing.daily-rollup-enabled:true}") boolean enabled,
            @Value("${agenthub.billing.daily-rollup-operator-id:0}") long operatorId) {
        this.daily = daily;
        this.enabled = enabled;
        this.operatorId = operatorId;
    }

    /**
     * 汇总「今天」日汇总。cron 默认 {@code 0 10,30,50 * * * ?}（每小时 10/30/50 分）。
     */
    @Scheduled(cron = "${agenthub.billing.daily-rollup-cron:0 10,30,50 * * * ?}", zone = "Asia/Shanghai")
    public void rollupToday() {
        if (!enabled) {
            return;
        }
        // 上一拍尚未结束：本拍放弃，等下一 10/30/50，避免堆积
        if (!running.compareAndSet(false, true)) {
            log.debug("[BILLING] 当日日汇总跳过：上一轮仍在执行");
            return;
        }
        LocalDate today = LocalDate.now(BIZ_ZONE);
        try {
            int dims = daily.rebuildRange(today, today, operatorId);
            log.info("[BILLING] 当日日汇总完成 bizDate={} dims={}", today, dims);
        } catch (RuntimeException e) {
            // 定时线程不得因单轮异常中断后续调度；下一拍会自然重来
            log.warn("[BILLING] 当日日汇总失败 bizDate={} type={} msg={}",
                    today, e.getClass().getSimpleName(), e.getMessage());
        } finally {
            running.set(false);
        }
    }
}
