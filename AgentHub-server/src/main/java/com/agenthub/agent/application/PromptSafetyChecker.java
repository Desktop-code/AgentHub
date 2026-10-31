package com.agenthub.agent.application;

import com.agenthub.agent.domain.service.AgentPromptProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 用户业务段保存前检查。所属域：agent。
 * 提示词是软约束：这里只拦超长和疑似密钥，覆盖系统规则与外连诱导默认只告警。
 * 工具能不能调用仍以绑定表为准，不靠这段文案扩权。
 */
@Component
public class PromptSafetyChecker {

    /** 密钥形态。只描述特征，不放真实密钥或完整 Token */
    private static final Pattern KEY_HINT = Pattern.compile("(?i)sk-|bearer\\s+|api[_-]?key|-----BEGIN");

    /** 连续 Base64，短英文单词不会撞上 */
    private static final Pattern LONG_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{40,}={0,2}(?![A-Za-z0-9+/])");

    /** 三段里前两段像 JWT，避免把普通句子当成密钥 */
    private static final Pattern JWT_LIKE = Pattern.compile("eyJ[A-Za-z0-9_\\-]{20,}\\.[A-Za-z0-9_\\-]{10,}");

    /** 想抹掉平台约束的说法。默认允许保存，运行时前缀仍在最前 */
    private static final Pattern OVERRIDE = Pattern.compile(
            "忽略(?:以上|之前|系统|平台)(?:规则|约束|提示)"
                    + "|(?i:ignore\\s+(?:all\\s+)?(?:previous|above|system)\\s+instructions)"
                    + "|(?i:</system>)"
                    + "|(?i:\\[SYSTEM\\])");

    /** 把对话弄到外部命令或地址的简单说法 */
    private static final Pattern EXFIL = Pattern.compile(
            "(?i)curl\\s+http|(?i)wget\\s+"
                    + "|(?:把|将).{0,20}(?:对话|聊天记录|内容).{0,40}(?:post|发送|上传).{0,40}https?://");

    private final int maxChars;
    private final boolean warnAsError;

    /**
     * @param properties 长度上限和告警是否升为拒绝
     */
    public PromptSafetyChecker(AgentPromptProperties properties) {
        int configured = properties.getPromptMaxChars();
        // 配置只能收紧。放宽超过绝对上限时仍按 AgentChecks，避免 TEXT 列被超长正文撑满
        if (configured <= 0 || configured > AgentChecks.PROMPT_MAX) {
            this.maxChars = AgentChecks.PROMPT_MAX;
        } else {
            this.maxChars = configured;
        }
        this.warnAsError = properties.isPromptWarnAsError();
    }

    /**
     * 按 Java char 计数，不用 Unicode 码点。空串允许，表示试跑只用平台前缀。
     *
     * @param userPrompt 已去掉首尾空白的用户段；null 当空
     * @return ok 为 false 时必须拒绝保存
     */
    public PromptCheckResult check(String userPrompt) {
        String text = userPrompt == null ? "" : userPrompt;
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (text.length() > maxChars) {
            errors.add("系统提示词超过长度，最长 " + maxChars);
        }
        if (looksLikeSecret(text)) {
            errors.add("请勿在提示词中填写密钥或 Token");
        }
        if (OVERRIDE.matcher(text).find()) {
            warnings.add("提示词含有覆盖系统规则的表述，运行时平台约束仍会加在前面");
        }
        if (EXFIL.matcher(text).find()) {
            warnings.add("提示词含有外连或导出对话的表述");
        }
        // 开关打开时，告警并进错误，调用方得到 4xx 而不是 200
        if (warnAsError && !warnings.isEmpty()) {
            errors.addAll(warnings);
            warnings.clear();
        }
        return new PromptCheckResult(errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings));
    }

    private boolean looksLikeSecret(String text) {
        if (text.isEmpty()) {
            return false;
        }
        return KEY_HINT.matcher(text).find() || LONG_TOKEN.matcher(text).find() || JWT_LIKE.matcher(text).find();
    }

    /**
     * 一次检查的结果。errors 非空则拒绝；warnings 默认不阻断。
     *
     * @param ok       可否入库
     * @param errors   拒绝原因，给前端红色提示
     * @param warnings 可保存的告警
     */
    public record PromptCheckResult(boolean ok, List<String> errors, List<String> warnings) {
    }
}
