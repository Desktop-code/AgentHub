package com.agenthub.prompt.domain.service;

import java.util.Map;
import java.util.Set;

/**
 * 平台锁定段落清单。所属域：prompt。
 * <p>
 * 职责：声明哪些用途的哪些段落属于「平台维护、Agent 与配置页都不能改」的硬约束。
 * 目前只有 MEMORY（记忆抽取）：
 * <ul>
 *   <li>{@code core_contract}（抽取契约）与 {@code output_format}（输出格式）必须随平台模板存在，
 *       保存草稿时内容不允许被改写，运行时也只拼这两段锁定正文；</li>
 *   <li>{@code memory_style} 是 v1 遗留的「输出格式」段，同样按锁定处理（防止旧 Agent 改回可覆盖），
 *       但它<b>不</b>参与运行时组装：旧 key/value/confidence 文案不得回流到抽取提示词。</li>
 * </ul>
 * <p>
 * 为什么放在 prompt 域而不是 memory 域：锁定口径服务于模板保存与运行时组装两处，
 * 必须只有一份；memory 域只消费「锁定正文 + 追加要求」，不重复定义哪些段被锁。
 * <p>
 * 线程安全：纯静态常量与方法，无状态。
 */
public final class PromptPlatformLocks {

    /** MEMORY 用途的记忆抽取段：契约、输出格式与新格式段 */
    private static final Set<String> MEMORY_LOCKED =
            Set.of("core_contract", "output_format", "memory_style");

    /** MEMORY 模板必须存在的锁定段（memory_style 是遗留段，存在即锁定，但不强制补齐） */
    private static final Set<String> MEMORY_REQUIRED = Set.of("core_contract", "output_format");

    /**
     * MEMORY 运行时真正参与组装的锁定段。
     * <p>
     * {@code memory_style} 只锁定编辑（防止旧 Agent 把它改回可覆盖），<b>不</b>参与出站：
     * 它承载的是 v1 的 key/value/confidence 旧格式，拼进去会把新输出格式带偏。
     */
    private static final Set<String> MEMORY_OUTBOUND = Set.of("core_contract", "output_format");

    /** 用途码 → 内容锁定段；未列出的用途返回空集合，保持原有可编辑口径 */
    private static final Map<String, Set<String>> LOCKED_BY_PURPOSE = Map.of("MEMORY", MEMORY_LOCKED);

    /** 用途码 → 必须存在的锁定段 */
    private static final Map<String, Set<String>> REQUIRED_BY_PURPOSE = Map.of("MEMORY", MEMORY_REQUIRED);

    /** 用途码 → 运行时参与组装的锁定段；未列出的用途沿用「全部锁定段参与组装」口径 */
    private static final Map<String, Set<String>> RUNTIME_BY_PURPOSE = Map.of("MEMORY", MEMORY_OUTBOUND);

    private PromptPlatformLocks() {
    }

    /**
     * 取该用途下内容被平台锁定的段键。
     *
     * @param purpose 用途码（大小写不敏感），可空
     * @return 锁定段键；无锁定口径时返回空集合
     */
    public static Set<String> lockedKeys(String purpose) {
        if (purpose == null || purpose.isBlank()) {
            return Set.of();
        }
        Set<String> keys = LOCKED_BY_PURPOSE.get(purpose.trim().toUpperCase(java.util.Locale.ROOT));
        return keys == null ? Set.of() : keys;
    }

    /**
     * 取该用途模板必须存在的锁定段键。
     *
     * @param purpose 用途码（大小写不敏感），可空
     * @return 必须存在的段键；无要求时返回空集合
     */
    public static Set<String> requiredKeys(String purpose) {
        if (purpose == null || purpose.isBlank()) {
            return Set.of();
        }
        Set<String> keys = REQUIRED_BY_PURPOSE.get(purpose.trim().toUpperCase(java.util.Locale.ROOT));
        return keys == null ? Set.of() : keys;
    }

    /**
     * 取该用途运行时真正参与组装的锁定段键。
     * <p>
     * 为空表示沿用旧口径「所有 locked 段都参与」；非空时只拼列出的段，
     * 用于排除「只锁定编辑、不参与出站」的遗留段（如 MEMORY 的 {@code memory_style}）。
     *
     * @param purpose 用途码（大小写不敏感），可空
     * @return 运行时锁定段键；未声明时返回空集合
     */
    public static Set<String> runtimeKeys(String purpose) {
        if (purpose == null || purpose.isBlank()) {
            return Set.of();
        }
        Set<String> keys = RUNTIME_BY_PURPOSE.get(purpose.trim().toUpperCase(java.util.Locale.ROOT));
        return keys == null ? Set.of() : keys;
    }

    /**
     * 判断某段正文是否由平台锁定、不允许页面保存或 Agent 覆盖。
     *
     * @param purpose 用途码，可空
     * @param segKey  段键，可空
     * @return true 表示内容锁定
     */
    public static boolean contentLocked(String purpose, String segKey) {
        return segKey != null && lockedKeys(purpose).contains(segKey.trim());
    }
}
