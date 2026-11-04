package com.agenthub.runtime.infra.agentscope;

import com.agenthub.agent.domain.service.PlatformPromptPrefix;
import com.agenthub.prompt.domain.model.PromptLayout;
import com.agenthub.runtime.domain.service.TrialFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 创建 ReActAgent 前的最后一道检查。所属域：runtime 的 infra。
 * <p>
 * 两项校验：
 * <ol>
 *   <li><b>前缀</b>：开关打开时，系统提示必须以平台约束（L1）开头，防止某条路径只喂用户段；</li>
 *   <li><b>结构</b>：带结构描述时，全部锁定段必须仍在正文里，且排在开放段之前——
 *       否则「锁定契约被截断或挤到业务语气之后」仍会照常出站，前缀校验对此无能为力。</li>
 * </ol>
 * <p>
 * 为什么结构校验要看 {@link PromptLayout} 而不是重新解析正文：段落边界只有组装侧知道，
 * 下游再靠关键字切一次就会得到第二套口径；布局里已带各段渲染后正文，直接定位即可。
 * <p>
 * 只做校验，不改写正文；失败时只记长度与段名，绝不把正文打进日志。
 */
@Component
public class AssembledPromptGuard {

    private static final Logger log = LoggerFactory.getLogger(AssembledPromptGuard.class);

    private final PlatformPromptPrefix prefixes;

    /**
     * @param prefixes 用来比对前缀开头
     */
    public AssembledPromptGuard(PlatformPromptPrefix prefixes) {
        this.prefixes = prefixes;
    }

    /**
     * @return 用于诊断的前缀对象（只读）
     */
    PlatformPromptPrefix owner() {
        return prefixes;
    }

    /**
     * 只做前缀校验。用于拿不到结构描述的链路（如 Router 父判定/汇总的手拼提示）。
     * <p>
     * 不改写正文。未带前缀时直接失败，不把原文打进日志。
     *
     * @param prompt 已经组装过的系统提示，可含 Skill 正文或路由候选
     * @return 原样返回，供 sysPrompt 使用
     * @throws TrialFailure 开关打开但前缀不在最前
     */
    public String require(String prompt) {
        String text = prompt == null ? "" : prompt;
        if (prefixes.isEnabled() && !prefixes.startsWithBase(text)) {
            log.warn("拒绝未带平台前缀的系统提示 prefixApplied=false promptChars={}", text.length());
            throw new TrialFailure("系统提示词未带平台前缀");
        }
        log.info("创建 Agent 前确认系统提示 prefixApplied={} promptChars={}", prefixes.isEnabled(), text.length());
        return text;
    }

    /**
     * 前缀 + 结构双重校验。试跑主链路必须走这里。
     *
     * @param prompt 已组装的系统提示
     * @param layout 组装时的结构描述；为空或 {@link PromptLayout#EMPTY} 时只做前缀校验
     * @return 原样返回，供 sysPrompt 使用
     * @throws TrialFailure 前缀不在最前，或锁定段缺失 / 被排到开放段之后
     */
    public String require(String prompt, PromptLayout layout) {
        String text = require(prompt);
        if (layout == null || !layout.structured()) {
            // 无结构信息：只保证前缀。正式链路必须在 TrialPromptFactory 里带上 layout
            return text;
        }
        // 锁定段必须在场：被预算裁掉或拼丢都意味着红线不在上下文里
        int lastLocked = -1;
        for (String block : layout.lockedBlocks()) {
            if (block == null || block.isBlank()) {
                continue;
            }
            int at = text.indexOf(block);
            if (at < 0) {
                log.warn("拒绝缺锁定段的系统提示 lockedBlocks={} promptChars={} headChars={}",
                        layout.lockedBlocks().size(), text.length(), layout.headChars());
                throw new TrialFailure("系统提示词缺少锁定段");
            }
            lastLocked = Math.max(lastLocked, at);
        }
        // 锁定段必须在开放段之前：取第一个仍在正文里的开放段位置做比较
        int firstOpen = Integer.MAX_VALUE;
        for (String block : layout.tailBlocks()) {
            if (block == null || block.isBlank()) {
                continue;
            }
            int at = text.indexOf(block);
            if (at >= 0 && at < firstOpen) {
                firstOpen = at;
            }
        }
        if (lastLocked >= 0 && firstOpen != Integer.MAX_VALUE && lastLocked > firstOpen) {
            log.warn("拒绝锁定段排在开放段之后的系统提示 lockedAt={} openAt={} promptChars={}",
                    lastLocked, firstOpen, text.length());
            throw new TrialFailure("系统提示词段落顺序异常：锁定段必须位于开放段之前");
        }
        log.info("创建 Agent 前确认系统提示 prefixApplied={} lockedBlocks={} headChars={} promptChars={}",
                prefixes.isEnabled(), layout.lockedBlocks().size(), layout.headChars(), text.length());
        return text;
    }
}
