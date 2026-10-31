package com.agenthub.runtime.infra.agentscope;

import com.agenthub.agent.domain.service.PlatformPromptPrefix;
import com.agenthub.runtime.domain.service.TrialFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 创建 ReActAgent 前的最后一道检查。所属域：runtime 的 infra。
 * 前缀开关打开时，系统提示必须以平台约束开头，防止某条路径只喂用户段。
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
}
