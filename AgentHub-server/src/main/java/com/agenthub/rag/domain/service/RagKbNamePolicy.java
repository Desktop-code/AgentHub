package com.agenthub.rag.domain.service;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.rag.domain.repository.RagKnowledgeBaseRepository;
import org.springframework.stereotype.Component;

/**
 * 未删除范围内 name 唯一。所属域：rag。
 * 表上只有普通索引（同名软删后允许复用名称），唯一性在这里保证。
 */
@Component
public class RagKbNamePolicy {

    private final RagKnowledgeBaseRepository kbs;

    /**
     * @param kbs 知识库仓储
     */
    public RagKbNamePolicy(RagKnowledgeBaseRepository kbs) {
        this.kbs = kbs;
    }

    /**
     * @param name      已去首尾空白的名称
     * @param excludeId 更新时排除当前行；新建为 null
     * @throws BizException 名称被占用时 DUPLICATE
     */
    public void assertUnique(String name, Long excludeId) {
        if (kbs.existsByName(name, excludeId)) {
            throw new BizException(ErrorCode.DUPLICATE, "知识库名称已存在");
        }
    }
}
