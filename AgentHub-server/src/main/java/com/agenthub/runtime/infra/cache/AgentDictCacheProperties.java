package com.agenthub.runtime.infra.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 运行期字典缓存参数。所属域：runtime。
 * <p>
 * 只由 {@code RuntimeInfrastructure} 的 {@code @EnableConfigurationProperties} 绑定，
 * 这里**不加** {@code @Component}：两者同时存在会把同一个前缀绑成两个 bean。
 * <p>
 * 为什么只有 Redis 层、没有进程内 L1：这些字典按 id 读、每次装配只读几条，
 * Redis 单次 GET 成本可忽略；而本地层在「改完立即生效」上天生有跨实例延迟
 * （写入口只能清本机），不值得为这点收益引入一致性坑。
 */
@Data
@ConfigurationProperties(prefix = "agenthub.agent.dict-cache")
public class AgentDictCacheProperties {

    /**
     * 总开关。关闭即整条链路退回直读库（排障用），功能不受影响，只是每轮多几次查询。
     */
    private boolean enabled = true;

    /**
     * Redis 存活秒数。写入口会在事务提交后显式失效，这里只是「漏失效」时的兜底。
     * 取 120 秒：既不会让漏失效长期存在，也不会让低频变更的字典频繁过期。
     */
    private int redisTtlSec = 120;

    /**
     * 「确认不存在」的负缓存秒数。很短：绑定指向已删除项时避免每轮打库，
     * 又能保证新建/恢复后快速自愈（写入口同样会显式失效）。
     */
    private int missTtlSec = 10;
}
