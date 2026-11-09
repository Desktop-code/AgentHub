package com.agenthub.spi.cache;

import java.util.Collection;
import java.util.List;

/**
 * 运行期字典缓存失效口。所属域：spi（跨域契约，只放接口，不放实现）。
 * <p>
 * 调用方：tool / skill 的写入口（工具项增删改与启停、工具源资料变更与删除、MCP 同步、Skill 编辑与发布）。
 * 实现方：runtime 的 infra（{@code AgentDictCacheInvalidatorImpl}），由它在**事务提交之后**删 Redis 键。
 * <p>
 * 为什么放在 spi：缓存是运行装配链（runtime）在读，写却在 tool / skill 域。
 * 让写入口只依赖一个窄接口，避免 tool / skill 直接依赖 runtime（方向反了），也避免各域各存一套 Redis 键。
 * <p>
 * 硬约束（实现方必须做到）：
 * 1. **必须 afterCommit**：事务内删键会被并发读者用提交前的旧值回填，形成「永久脏读无失效路径」；
 * 2. 入参为 null / 空集合视为无操作；不校验 id 是否存在；
 * 3. 删失败只记日志，靠 TTL 兜底，**禁止**把异常抛回写接口（写成功不能被缓存故障拖失败）；
 * 4. 这里只失效 tool_item / tool_source / skill_def 三类字典，不涉及运行会话、对话与记忆。
 * <p>
 * 线程安全：无状态；回调在事务提交线程执行。
 */
public interface AgentDictCacheInvalidator {

    /**
     * 空实现：缓存关闭、单测或不需要失效的装配场景使用。
     * 用常量而不是 null，调用方不必判空。
     */
    AgentDictCacheInvalidator NOOP = new AgentDictCacheInvalidator() {
        @Override
        public void evictToolItems(Collection<Long> itemIds) {
        }

        @Override
        public void evictToolSource(Long sourceId) {
        }

        @Override
        public void evictSkills(Collection<Long> skillIds) {
        }
    };

    /**
     * 失效一批工具项快照。
     *
     * @param itemIds 工具项主键；可空
     */
    void evictToolItems(Collection<Long> itemIds);

    /**
     * 失效一个工具源快照，并连带失效其下「当前未删除」工具项的快照。
     * <p>
     * 注意：删除源时（源下工具项已逻辑删除）查询不到被删项，调用方需先用
     * {@link #evictToolItems(Collection)} 补上删除前取到的 id，否则那些键只能等 TTL 自然过期。
     *
     * @param sourceId 工具源主键；可空
     */
    void evictToolSource(Long sourceId);

    /**
     * 失效一批 Skill 定义快照（生效版本正文会随之变化）。
     *
     * @param skillIds Skill 主键；可空
     */
    void evictSkills(Collection<Long> skillIds);

    /**
     * 单条工具项失效的便捷方法。
     *
     * @param itemId 工具项主键；可空
     */
    default void evictToolItem(Long itemId) {
        evictToolItems(itemId == null ? List.of() : List.of(itemId));
    }

    /**
     * 单条 Skill 失效的便捷方法。
     *
     * @param skillId Skill 主键；可空
     */
    default void evictSkill(Long skillId) {
        evictSkills(skillId == null ? List.of() : List.of(skillId));
    }
}
