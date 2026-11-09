package com.agenthub.runtime.infra.idempotency;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 发消息幂等参数。所属域：runtime。
 * <p>
 * 只由 {@code RuntimeInfrastructure} 的 {@code @EnableConfigurationProperties} 绑定，
 * 这里不加 {@code @Component}，避免同一前缀被绑成两个 bean。
 */
@Data
@ConfigurationProperties(prefix = "agenthub.runtime.idempotency")
public class TrialIdempotencyProperties {

    /**
     * 总开关。关闭后带键请求也按「无键」直跑（排障用），语义与改造前一致：可能重复执行。
     */
    private boolean enabled = true;

    /**
     * 幂等记录在 Redis 的存活秒数。DB 行不设 TTL，客户端在同一键上重试仍可命中终态；
     * Redis 过期后回落到 DB 判定。取 1800 秒：覆盖一次运行 + 客户端重试窗口。
     */
    private int ttlSec = 1800;

    /**
     * RUNNING 陈旧窗口（秒）。超过这个时长仍未收尾的占位（进程崩溃、发布重启）允许被同键请求接管，
     * 否则该键会被永久占死。取值须大于平台单轮墙钟硬顶（默认 300 秒），留出排队与重试余量。
     */
    private int staleSec = 600;
}
