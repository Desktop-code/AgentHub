package com.agenthub.skill.domain.service;

import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.skill.domain.model.SkillDef;
import com.agenthub.skill.domain.model.SkillQuery;
import com.agenthub.skill.domain.repository.SkillDefRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 未删除的同名 Skill 不能再建一条。排除自身后允许原名保存。
 */
class SkillNamePolicyTest {

    @Test
    void duplicateNameRejectedUnlessItIsTheSameRow() {
        SkillDefRepository skills = new MemorySkills("guide", 7L);
        SkillNamePolicy policy = new SkillNamePolicy(skills);
        assertThrows(BizException.class, () -> policy.assertUnique("guide", null));
        assertThrows(BizException.class, () -> policy.assertUnique("guide", 8L));
        assertDoesNotThrow(() -> policy.assertUnique("guide", 7L));
        assertDoesNotThrow(() -> policy.assertUnique("other", null));
    }

    /**
     * 只实现唯一性判断需要的方法，其余调用说明测试写错了。
     */
    private static final class MemorySkills implements SkillDefRepository {

        private final String name;
        private final Long id;

        private MemorySkills(String name, Long id) {
            this.name = name;
            this.id = id;
        }

        @Override
        public boolean existsByName(String candidate, Long excludeId) {
            return name.equals(candidate) && (excludeId == null || !excludeId.equals(id));
        }

        @Override
        public PageResult<SkillDef> page(SkillQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<SkillDef> findById(Long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Long insert(SkillDef skill, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateProfile(SkillDef skill, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateActiveMirror(Long id, String version, String description, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateStatus(Long id, int status, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void logicalDelete(Long id, Long operatorId) {
            throw new UnsupportedOperationException();
        }
    }
}
