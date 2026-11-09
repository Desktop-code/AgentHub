package com.agenthub.auth.infra.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 访问快照缓存参数。所属域：auth。
 * <p>
 * 只由 {@code AuthCacheInfrastructure} 的 {@code @EnableConfigurationProperties} 绑定，
 * 这里**不加** {@code @Component}：两者同时存在会把同一个前缀绑成两个 bean。
 * <p>
 * 只做 Redis 层、不做进程内 L1：权限与菜单要求「写完立刻生效」，
 * 本地层只能被本机写入口清掉，多实例下会出现「这台已生效、那台还旧」的错觉。
 */
@Data
@ConfigurationProperties(prefix = "agenthub.auth.acl-cache")
public class AclCacheProperties {

    /**
     * 总开关。关闭即整链退回直读库（排障用），权限语义不变，只是每个请求多打 1~5 次查询。
     */
    private boolean enabled = true;

    /**
     * 快照在 Redis 的存活秒数。写入口会让版本推进或精确删键，TTL 只是「漏失效」时的兜底。
     * 取 120 秒：管理员改权限后即使极端情况下漏了失效，最多两分钟内自愈。
     */
    private int redisTtlSec = 120;
}
