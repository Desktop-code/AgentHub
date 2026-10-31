package com.agenthub.prompt.domain.repository;

import com.agenthub.prompt.domain.model.PromptConstraintSnapshot;
import com.agenthub.prompt.domain.model.PromptTemplateRef;
import com.agenthub.prompt.domain.model.PromptTemplateSnapshot;

import java.util.Optional;

/**
 * 运行时提示词快照读取口。所属域：prompt。
 * <p>
 * 调用方：{@code PromptAssembler}（经缓存实现）。
 * 实现方：DB 直读（{@code DbPromptSnapshotProvider}）与带 Caffeine/Redis 的缓存装饰器。
 * 约束：
 * 1. 只返回**已发布**行（status=1）；草稿永不进入运行时，避免「保存即上线」。
 * 2. 未取到返回 {@code Optional.empty()}，不得用空串/默认文案冒充命中。
 * 3. 返回的快照视为不可变，调用方不得回写。
 */
public interface PromptSnapshotProvider {

    /**
     * @param locale 语言
     * @return 该 locale 当前生效的 L1
     */
    Optional<PromptConstraintSnapshot> activeConstraint(String locale);

    /**
     * 按业务键 + 版本取钉死快照。
     *
     * @param templateKey 业务键
     * @param locale      语言
     * @param version     版本号
     * @return 已发布快照；不存在或为草稿时为空
     */
    Optional<PromptTemplateSnapshot> templateByKey(String templateKey, String locale, int version);

    /**
     * @param id 主键
     * @return 已发布快照；不存在或为草稿时为空
     */
    Optional<PromptTemplateSnapshot> templateById(Long id);

    /**
     * @param purpose 用途码
     * @param locale  语言
     * @return 该用途当前生效模板的轻量引用
     */
    Optional<PromptTemplateRef> activeRefByPurpose(String purpose, String locale);

    /**
     * @param templateKey 业务键
     * @param locale      语言
     * @return 该键当前生效模板的轻量引用
     */
    Optional<PromptTemplateRef> activeRefByKey(String templateKey, String locale);
}
