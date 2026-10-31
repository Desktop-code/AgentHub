package com.agenthub.prompt.infra.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 提示词缓存参数。所属域：prompt。
 * <p>
 * 三层缓存（Caffeine → Redis → DB）各自的存活时间在这里集中配置，
 * 禁止散落到各实现里写死数字。
 * <p>
 * 只由 {@code PromptInfrastructure} 的 {@code @EnableConfigurationProperties} 绑定，
 * 这里**不加** {@code @Component}：两者同时存在会把同一个前缀绑成两个 bean。
 */
@Data
@ConfigurationProperties(prefix = "agenthub.prompt.cache")
public class PromptCacheProperties {

    /** 进程内条目上限。配置态数据量很小，几百条足够 */
    private int localMaxSize = 512;
    /** 进程内 L1/L2 存活秒数。发布时会主动失效，这里只是兜底 */
    private int localTtlSec = 60;
    /** Redis 存活秒数。多实例共享，TTL 比本地长一档 */
    private int redisTtlSec = 600;
    /**
     * 未命中（无生效 L1 / 模板不存在）的负缓存秒数。
     * 很短：既避免缺失配置把 DB 打穿，又保证发布后能快速自愈（发布同时会显式失效）。
     */
    private int missTtlSec = 10;
}
