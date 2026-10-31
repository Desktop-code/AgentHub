package com.agenthub.prompt.domain.service;

/**
 * 提示词缓存键。所属域：prompt。
 * <p>
 * 键口径见架构定稿 §5.3，集中在此，禁止各调用点自己拼串：
 * <ul>
 *   <li>{@code prompt:l1:{locale}:active} —— L1 生效正文</li>
 *   <li>{@code prompt:l2:{templateKey}:{locale}:{version}} —— 钉死版本的完整快照（含段落）</li>
 *   <li>{@code prompt:l2:{templateKey}:{locale}:active} —— 管理端预览/未钉死配置用的生效引用</li>
 *   <li>{@code prompt:l2p:{purpose}:{locale}:active} —— 按用途取生效引用（Assembler 主路径）</li>
 *   <li>{@code prompt:l2id:{id}} —— 按主键取钉死快照（CHAT 挂 id 时走这条）</li>
 * </ul>
 * 钉死版与 active 分开：平台发新版只影响 active 键，已钉死的 Agent 读到的是自己那一版的快照。
 */
public final class PromptCacheKeys {

    private static final String ROOT = "prompt";

    private PromptCacheKeys() {
    }

    /**
     * @param locale 语言
     * @return L1 生效正文键
     */
    public static String constraint(String locale) {
        return ROOT + ":l1:" + safe(locale) + ":active";
    }

    /**
     * @param templateKey 业务键
     * @param locale      语言
     * @param version     版本
     * @return 钉死版快照键
     */
    public static String templateVersion(String templateKey, String locale, int version) {
        return ROOT + ":l2:" + safe(templateKey) + ":" + safe(locale) + ":" + version;
    }

    /**
     * @param templateKey 业务键
     * @param locale      语言
     * @return 按键的生效引用
     */
    public static String templateActive(String templateKey, String locale) {
        return ROOT + ":l2:" + safe(templateKey) + ":" + safe(locale) + ":active";
    }

    /**
     * @param purpose 用途码
     * @param locale  语言
     * @return 按用途的生效引用
     */
    public static String purposeActive(String purpose, String locale) {
        return ROOT + ":l2p:" + safe(purpose) + ":" + safe(locale) + ":active";
    }

    /**
     * @param id 模板主键
     * @return 按主键的钉死快照
     */
    public static String templateById(Long id) {
        return ROOT + ":l2id:" + (id == null ? "0" : id);
    }

    /**
     * 键片段清洗：禁止冒号与空白进键，避免拼出歧义键。
     */
    private static String safe(String raw) {
        if (raw == null || raw.isBlank()) {
            return "_";
        }
        return raw.trim().replace(':', '_').replace(' ', '_');
    }
}
