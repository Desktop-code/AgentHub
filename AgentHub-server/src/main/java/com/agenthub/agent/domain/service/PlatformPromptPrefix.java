package com.agenthub.agent.domain.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 平台安全前缀（L1 只读视图）。所属域：agent。
 * <p>
 * 本类**不再持有文案**：L1 的正源是 {@code prompt_global_constraint} 当前生效行，
 * 由 {@link PlatformConstraintSource}（实现落在 prompt 域，内部走 PromptAssembler）提供。
 * 组装、配置页展示、出站前校验全部走这一条，禁止双轨。
 * <p>
 * classpath 资源仍在，但角色从「正源」降为「种子来源」：{@code sql/data-init-prompt-l1-seed.sql}
 * 的内容与它一致，运行时**不再**读取它决定行为。
 * <ul>
 *   <li>未发布 L1 时 {@link #textFor(String)} 返回空串 → {@code AssembledPromptGuard} 阻断出站；</li>
 *   <li>{@code agenthub.agent.prompt-prefix-enabled=false} 时同样返回空串（排障/本地实验）。</li>
 * </ul>
 * 启动时不再因资源缺失而失败：文案已入库，种子文件缺失不是运行条件。
 * 线程安全：无状态；源实现须可并发调用。
 */
@Component
public class PlatformPromptPrefix {

    private static final Logger log = LoggerFactory.getLogger(PlatformPromptPrefix.class);

    /** ROUTER 追加句的默认文案；仅当源与配置都没有时使用，不参与开头比对 */
    private static final String DEFAULT_ROUTER_EXTRA =
            "你是路由型助手：根据用户意图选择最合适的子 Agent，不要代替子 Agent 执行其专业工具链"
                    + "（除非未匹配到子节点且必须直接回答）。";

    private final boolean enabled;
    private final PlatformConstraintSource source;
    private final String routerExtra;

    /**
     * Spring 装配用。
     * <p>
     * 类上有两个 public 构造器，因此必须显式标注容器使用哪一个：
     * 否则 Spring 既不满足「唯一构造器」也不满足「无参构造器」，会直接抛
     * {@code No default constructor found} 让整个容器起不来。
     *
     * @param properties     开关与 ROUTER 追加句资源路径
     * @param resourceLoader 读 classpath 的 ROUTER 追加句
     * @param source         L1 生效正文来源；用 ObjectProvider 容忍 prompt 域尚未装配的情况
     */
    @Autowired
    public PlatformPromptPrefix(AgentPromptProperties properties, ResourceLoader resourceLoader,
                                ObjectProvider<PlatformConstraintSource> source) {
        this(properties, source == null ? null : source.getIfAvailable(),
                readQuietly(resourceLoader, properties.getRouterExtraPathOrDefault()));
    }

    /**
     * 测试与手动装配用：直接给定源与 ROUTER 追加句。
     *
     * @param properties  开关
     * @param source      L1 来源，可空（为空时视为无生效 L1）
     * @param routerExtra ROUTER 追加句，可空
     */
    public PlatformPromptPrefix(AgentPromptProperties properties, PlatformConstraintSource source,
                                String routerExtra) {
        this.enabled = properties.isPromptPrefixEnabled();
        this.source = source;
        String extra = routerExtra == null ? "" : routerExtra.trim();
        this.routerExtra = extra.isEmpty() ? DEFAULT_ROUTER_EXTRA : extra;
        log.info("平台前缀已就绪 source={}", this.source == null ? "none" : "db");
    }

    /**
     * @return true 表示创建 Agent 时系统提示必须以平台约束开头
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 按运行模式取只读前缀。REACT / PLAN 只有基础约束；ROUTER 之后追加路由句。
     *
     * @param runMode REACT / ROUTER / PLAN_AND_EXECUTE，其它按 REACT
     * @return 当前生效 L1；未发布或开关关闭时为空串
     */
    public String textFor(String runMode) {
        String base = baseText();
        if (base.isEmpty()) {
            return "";
        }
        if ("ROUTER".equals(runMode)) {
            return base + "\n\n" + routerExtra;
        }
        return base;
    }

    /**
     * 出站前校验：组装结果是否以「当前生效 L1」开头。
     * 比较对象是库中生效正文而不是文件，因此 L1 换版后旧校验不会误放行。
     *
     * @param prompt 即将交给模型的系统提示
     * @return true 表示带上了当前生效约束
     */
    public boolean startsWithBase(String prompt) {
        String base = baseText();
        return enabled && !base.isEmpty() && prompt != null && prompt.startsWith(base);
    }

    /**
     * 当前生效 L1 基础约束。ROUTER 的路由句由 {@link #textFor(String)} 追加，不参与开头比对。
     *
     * @return 生效正文；无生效版时为空串（调用方据此阻断）
     */
    public String baseText() {
        if (!enabled || source == null) {
            return "";
        }
        Optional<String> text = source.activeConstraintText(null);
        return text == null ? "" : text.orElse("");
    }

    /**
     * ROUTER 场景的平台路由说明。它属于平台侧（非用户可编辑），
     * 由 {@code PromptContextAdapters} 放进 {@code PromptContext#constraintExtra}，
     * 紧跟 L1 之后、L2 之前，位置等价于原先「前缀 + 路由句」的拼法。
     *
     * @return RO 路由说明；前缀关闭或源缺失时为空串
     */
    public String routerClause() {
        return enabled ? routerExtra : "";
    }

    private static String readQuietly(ResourceLoader resourceLoader, String location) {
        if (resourceLoader == null || location == null || location.isBlank()) {
            return null;
        }
        try {
            Resource resource = resourceLoader.getResource(location);
            if (!resource.exists()) {
                return null;
            }
            try (InputStream in = resource.getInputStream()) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                        .replace("\r\n", "\n")
                        .replace('\r', '\n')
                        .trim();
                return text.isEmpty() ? null : text;
            }
        } catch (IOException ex) {
            log.info("ROUTER 追加句资源读取失败，使用内置默认 location={}", location);
            return null;
        }
    }
}
