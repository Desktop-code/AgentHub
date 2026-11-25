package com.agenthub.agent.domain.service;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentQuery;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 未删除的同编码 Agent 不能再建一条。排除自身后允许原编码保存。
 */
class AgentCodePolicyTest {

    @Test
    void duplicateCodeRejectedUnlessItIsTheSameRow() {
        AgentCodePolicy policy = new AgentCodePolicy(new MemoryAgents("guide", 7L));
        assertThrows(BizException.class, () -> policy.assertUnique("guide", null));
        assertThrows(BizException.class, () -> policy.assertUnique("guide", 8L));
        assertDoesNotThrow(() -> policy.assertUnique("guide", 7L));
        assertDoesNotThrow(() -> policy.assertUnique("other", null));
    }

    /**
     * 只实现唯一性判断需要的方法，其余调用说明测试写错了。
     */
    private static final class MemoryAgents implements AgentDefRepository {

        private final String code;
        private final Long id;

        private MemoryAgents(String code, Long id) {
            this.code = code;
            this.id = id;
        }

        @Override
        public boolean existsByCode(String candidate, Long excludeId) {
            return code.equals(candidate) && (excludeId == null || !excludeId.equals(id));
        }

        @Override
        public PageResult<AgentDef> page(AgentQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<AgentDef> findById(Long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<AgentDef> findByIdIncludingDeleted(Long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.List<AgentDef> findByIds(java.util.Collection<Long> ids) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.List<AgentDef> listOrchestrable() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Long insert(AgentDef agent, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateProfile(AgentDef agent, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateRuntimeConfig(Long id, String runtimeConfigJson, Long operatorId) {
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
