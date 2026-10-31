package com.agenthub.prompt.domain.repository;

/**
 * 提示词缓存失效口。所属域：prompt。
 * <p>
 * 调用方：L1 / L2 的发布用例（{@code PromptConstraintAppService#publish}、
 * {@code PromptTemplateAppService#publish}）。
 * 实现方：缓存装饰器（Caffeine 本地 + Redis 共享）。
 * <p>
 * 约束：发布与失效必须可对齐——实现须在**事务提交后**才删缓存，
 * 否则并发读者可能把提交前的旧值重新写回缓存，造成「无失效路径的永久脏读」。
 * 允许短暂旧读，不允许永久脏读。
 */
public interface PromptCacheInvalidator {

    /**
     * 某 locale 的 L1 生效正文被切换。
     *
     * @param locale 语言
     */
    void evictConstraint(String locale);

    /**
     * 某业务键（同 locale）的生效引用被切换。
     *
     * @param templateKey 业务键
     * @param locale      语言
     */
    void evictTemplateRef(String templateKey, String locale);

    /**
     * 某用途（同 locale）的生效引用被切换。
     *
     * @param purpose 用途码
     * @param locale  语言
     */
    void evictPurposeRef(String purpose, String locale);
}
