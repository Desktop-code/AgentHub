package com.agenthub.memory.application;

import com.agenthub.memory.domain.service.MemoryTexts;
import com.agenthub.memory.infra.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 显式「记住」的 L0 正则快筛。所属域：memory 的 application。
 *
 * <p>为什么做成组件而不是继续放在 {@link MemoryTexts}：内置短语需要能被
 * {@code agenthub.memory.remember-patterns} 覆盖，而正则只该编译一次。这里在启动时
 * 把配置（或代码默认）编译成 {@link Pattern}，主请求线程每轮只做 {@code find()}，不重复编译。
 *
 * <p>关键约束：
 * <ol>
 *   <li>只作用于「用户当轮原文」的显式通道；会话结束 / 摘要批抽不经过本类；</li>
 *   <li>先判排除再判命中：「记住了吗」包含「记住」但属于询问，必须排除；</li>
 *   <li><b>不做长度过滤</b>：「记住它」这种极短指令必须能命中；</li>
 *   <li>yml 里单条正则写坏时只跳过该条并打 warn，不影响其它规则与应用启动。</li>
 * </ol>
 */
@Component
public class RememberIntentDetector {

    private static final Logger log = LoggerFactory.getLogger(RememberIntentDetector.class);

    /** 编译后的命中规则 */
    private final List<Pattern> includes;
    /** 编译后的排除规则；命中任意一条即判否 */
    private final List<Pattern> excludes;

    /**
     * @param properties 记忆平台配置；remember-patterns 为空时使用代码默认
     */
    public RememberIntentDetector(MemoryProperties properties) {
        List<String> configured = properties == null ? null : properties.getRememberPatterns();
        List<String> includeTexts = configured == null || configured.isEmpty()
                ? MemoryTexts.DEFAULT_REMEMBER_PATTERNS
                : configured;
        this.includes = compile(includeTexts, "remember-patterns");
        this.excludes = compile(MemoryTexts.DEFAULT_REMEMBER_EXCLUSIONS, "remember-exclusions");
    }

    /**
     * 当轮用户原文是否像明确要求「记住」。
     *
     * @param text 用户原文，可空
     * @return true 表示 L0 命中，可跳过 L1 直接异步 L2
     */
    public boolean wantsRemember(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        // 排除优先：必须挡在命中之前，否则「记住了吗」会被「记住」误捞
        for (Pattern pattern : excludes) {
            if (pattern.matcher(text).find()) {
                return false;
            }
        }
        for (Pattern pattern : includes) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 批量编译；单条非法正则只跳过自身，不阻断启动。
     *
     * @param raw    正则片段
     * @param source 配置来源，仅用于日志
     * @return 编译成功的规则
     */
    private static List<Pattern> compile(List<String> raw, String source) {
        List<Pattern> patterns = new ArrayList<>();
        if (raw == null) {
            return patterns;
        }
        for (String one : raw) {
            if (one == null || one.isBlank()) {
                continue;
            }
            try {
                patterns.add(Pattern.compile(one.trim()));
            } catch (PatternSyntaxException ex) {
                log.warn("忽略非法的记忆快筛正则 source={} pattern={} msg={}",
                        source, one, ex.getDescription());
            }
        }
        return patterns;
    }
}
