package com.agenthub.runtime.domain.service;

/**
 * 运行期字典缓存键。所属域：runtime。
 * <p>
 * 只缓「按 id 读、极少变更」的三类字典（见扫描报告 C3 的落地建议）：
 * <ul>
 *   <li>{@code agenthub:agent:dict:tool-item:{id}} —— 工具项（名称/说明/schema/启停）</li>
 *   <li>{@code agenthub:agent:dict:tool-source:{id}} —— 工具源**无密投影**（id/名称/类型/启停）</li>
 *   <li>{@code agenthub:agent:dict:skill-def:{id}} —— Skill 定义（标题/场景/触发词/生效正文/启停）</li>
 * </ul>
 * 约定：
 * 1. 一律带 {@code agenthub:} 根前缀（prompt 域现存键不带前缀属历史口径，新键不跟随）；
 * 2. 片段只用主键（自增长整型），天然不含冒号与空白，无需额外清洗；
 * 3. 禁止在调用点自己拼串；新增字典只在本类加方法；
 * 4. 工具源**不缓存** authHeaderCipher / authHeaderMask / baseUrl —— 密文与出站地址不进 Redis。
 */
public final class AgentDictCacheKeys {

    /** 根前缀：域 + 用途，便于按前缀排查与手工清理 */
    private static final String ROOT = "agenthub:agent:dict";

    private AgentDictCacheKeys() {
    }

    /**
     * @param itemId 工具项主键
     * @return 工具项快照键
     */
    public static String toolItem(Long itemId) {
        return ROOT + ":tool-item:" + id(itemId);
    }

    /**
     * @param sourceId 工具源主键
     * @return 工具源无密投影键
     */
    public static String toolSource(Long sourceId) {
        return ROOT + ":tool-source:" + id(sourceId);
    }

    /**
     * @param skillId Skill 主键
     * @return Skill 定义快照键
     */
    public static String skillDef(Long skillId) {
        return ROOT + ":skill-def:" + id(skillId);
    }

    /**
     * 主键归一：null 统一成 0，保证键形态稳定（null 本身在读取口已被挡下）。
     */
    private static String id(Long id) {
        return id == null ? "0" : String.valueOf(id);
    }
}
