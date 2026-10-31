package com.agenthub.prompt.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.prompt.api.dto.PromptConstraintDraftReq;
import com.agenthub.prompt.api.dto.PromptConstraintListReq;
import com.agenthub.prompt.api.dto.PromptConstraintResp;
import com.agenthub.prompt.domain.enums.PromptPublishStatus;
import com.agenthub.prompt.domain.model.PromptConstraint;
import com.agenthub.prompt.domain.repository.PromptCacheInvalidator;
import com.agenthub.prompt.domain.repository.PromptConstraintRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * L1 平台约束用例。所属域：prompt。
 * 草稿可改；发布时同 locale 原 is_active 置 0、本版置 1。
 * 操作日志只记 locale/version/动作，不写 content 全文。
 */
@Service
public class PromptConstraintAppService {

    private final PromptConstraintRepository constraints;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;
    private final PromptCacheInvalidator cacheInvalidator;

    /**
     * @param constraints      L1 仓储
     * @param operatorContext  操作人
     * @param operLogWriter    操作日志
     * @param cacheInvalidator 发布后清 L1 缓存；运行时不再穿透 MySQL 依赖它
     */
    public PromptConstraintAppService(PromptConstraintRepository constraints,
                                      OperatorContext operatorContext,
                                      OperLogWriter operLogWriter,
                                      PromptCacheInvalidator cacheInvalidator) {
        this.constraints = constraints;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
        this.cacheInvalidator = cacheInvalidator;
    }

    /**
     * @param req 可选 locale；空则默认 zh-CN
     * @return 该 locale 下全部版本（含正文，供管理端编辑）
     */
    public List<PromptConstraintResp> list(PromptConstraintListReq req) {
        String locale = PromptChecks.locale(req == null ? null : req.getLocale());
        return constraints.listByLocale(locale).stream().map(this::toResp).toList();
    }

    /**
     * @param id 主键
     * @return 详情
     */
    public PromptConstraintResp get(Long id) {
        return toResp(require(id));
    }

    /**
     * 保存草稿：无 id 新建下一版本；有 id 仅允许更新草稿正文。
     *
     * @param req 草稿
     * @return 保存后详情
     */
    @Transactional
    public PromptConstraintResp saveDraft(PromptConstraintDraftReq req) {
        String content = PromptChecks.requiredContent(req.getContent(), "content");
        Long operatorId = operatorContext.requireUserId();
        if (req.getId() == null) {
            String locale = PromptChecks.locale(req.getLocale());
            int next = constraints.maxVersion(locale) + 1;
            PromptConstraint row = new PromptConstraint();
            row.setLocale(locale);
            row.setContent(content);
            row.setVersion(next);
            row.setStatus(PromptPublishStatus.DRAFT.getCode());
            row.setIsActive(0);
            Long id = constraints.insert(row, operatorId);
            operLogWriter.write("prompt", "l1_draft", "prompt_global_constraint", String.valueOf(id),
                    "L1 新建草稿 locale=" + locale + " v" + next);
            return get(id);
        }
        PromptConstraint existing = require(req.getId());
        PromptChecks.requireDraft(existing.getStatus());
        // 已发布禁止改 locale；草稿也固定 locale，避免 uk_locale_ver 漂移
        existing.setContent(content);
        constraints.updateDraftContent(existing, operatorId);
        operLogWriter.write("prompt", "l1_draft", "prompt_global_constraint", String.valueOf(existing.getId()),
                "L1 更新草稿 locale=" + existing.getLocale() + " v" + existing.getVersion());
        return get(existing.getId());
    }

    /**
     * 发布草稿：清同 locale 生效行，本版 status=已发布且 is_active=1。
     *
     * @param id 草稿主键
     * @return 发布后详情
     */
    @Transactional
    public PromptConstraintResp publish(Long id) {
        PromptConstraint existing = require(id);
        PromptChecks.requireDraftForPublish(existing.getStatus());
        Long operatorId = operatorContext.requireUserId();
        // 先清后置，保证同 locale 至多一条生效
        constraints.clearActive(existing.getLocale(), operatorId);
        constraints.markPublishedActive(id, operatorId);
        // 发布时间点必须清缓存，否则只能等 TTL；失效在事务提交后执行，避免旧值被回填
        cacheInvalidator.evictConstraint(existing.getLocale());
        operLogWriter.write("prompt", "l1_publish", "prompt_global_constraint", String.valueOf(id),
                "L1 发布 locale=" + existing.getLocale() + " v" + existing.getVersion());
        return get(id);
    }

    /**
     * @param id 主键
     * @return 未删除约束
     */
    private PromptConstraint require(Long id) {
        return constraints.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "平台约束不存在"));
    }

    private PromptConstraintResp toResp(PromptConstraint domain) {
        PromptConstraintResp resp = new PromptConstraintResp();
        resp.setId(domain.getId());
        resp.setLocale(domain.getLocale());
        resp.setContent(domain.getContent());
        resp.setVersion(domain.getVersion());
        resp.setStatus(domain.getStatus());
        resp.setIsActive(domain.getIsActive());
        resp.setCreateBy(domain.getCreateBy());
        resp.setUpdateBy(domain.getUpdateBy());
        resp.setCreateTime(domain.getCreateTime());
        resp.setUpdateTime(domain.getUpdateTime());
        return resp;
    }
}
