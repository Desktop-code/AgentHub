package com.agenthub.prompt.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.prompt.api.dto.PromptSegmentReq;
import com.agenthub.prompt.api.dto.PromptSegmentResp;
import com.agenthub.prompt.api.dto.PromptTemplateDraftReq;
import com.agenthub.prompt.api.dto.PromptTemplatePageReq;
import com.agenthub.prompt.api.dto.PromptTemplateResp;
import com.agenthub.prompt.domain.enums.PromptPublishStatus;
import com.agenthub.prompt.domain.model.PromptSegment;
import com.agenthub.prompt.domain.model.PromptTemplate;
import com.agenthub.prompt.domain.model.PromptTemplateQuery;
import com.agenthub.prompt.domain.repository.PromptCacheInvalidator;
import com.agenthub.prompt.domain.repository.PromptSegmentRepository;
import com.agenthub.prompt.domain.repository.PromptTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * L2 用途模板用例。所属域：prompt。
 * 草稿保存同步主表与段落；发布切换同 purpose+locale 的 is_active。
 * 操作日志不写段落正文。
 */
@Service
public class PromptTemplateAppService {

    private final PromptTemplateRepository templates;
    private final PromptSegmentRepository segments;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;
    private final PromptCacheInvalidator cacheInvalidator;

    /**
     * @param templates       主表仓储
     * @param segments        段落仓储
     * @param operatorContext 操作人
     * @param operLogWriter   操作日志
     * @param cacheInvalidator 发布后清 L2 生效引用缓存
     */
    public PromptTemplateAppService(PromptTemplateRepository templates,
                                    PromptSegmentRepository segments,
                                    OperatorContext operatorContext,
                                    OperLogWriter operLogWriter,
                                    PromptCacheInvalidator cacheInvalidator) {
        this.templates = templates;
        this.segments = segments;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
        this.cacheInvalidator = cacheInvalidator;
    }

    /**
     * @param req 分页与筛选
     * @return 不含段落的一页
     */
    public PageResult<PromptTemplateResp> page(PromptTemplatePageReq req) {
        PromptTemplateQuery query = new PromptTemplateQuery();
        query.setCurrent(req.getCurrent());
        query.setSize(req.getSize());
        query.setKeyword(req.getKeyword());
        // 精确键优先：中栏按对象取全部版本时用 templateKey，不走模糊关键词
        query.setTemplateKey(req.getTemplateKey());
        query.setPurpose(req.getPurpose());
        query.setLocale(req.getLocale());
        query.setStatus(req.getStatus());
        query.setIsActive(req.getIsActive());
        PageResult<PromptTemplate> page = templates.page(query);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(row -> toResp(row, List.of())).toList());
    }

    /**
     * @param id 主键
     * @return 含段落详情
     */
    public PromptTemplateResp get(Long id) {
        PromptTemplate row = require(id);
        return toResp(row, segments.listByTemplateId(id));
    }

    /**
     * 保存草稿。无 id：按 templateKey+locale 开新版本；有 id：仅草稿可改。
     *
     * @param req 主信息 + 段落
     * @return 详情
     */
    @Transactional
    public PromptTemplateResp saveDraft(PromptTemplateDraftReq req) {
        PromptChecks.assertSegments(req.getSegments(), PromptSegmentReq::getSegKey);
        List<PromptSegment> segs = toSegments(req.getSegments());
        Long operatorId = operatorContext.requireUserId();

        if (req.getId() == null) {
            String key = PromptChecks.templateKey(req.getTemplateKey());
            String locale = PromptChecks.locale(req.getLocale());
            String purpose = PromptChecks.purpose(req.getPurpose());
            // 左栏是固定用途槽位：同 purpose+locale 只允许一个 template_key，
            // 新建草稿必须落在该用途已有键（或首个键）上，禁止再造第二个键
            assertSingleKeyPerPurpose(purpose, locale, key);
            int next = templates.maxVersion(key, locale) + 1;
            PromptTemplate row = new PromptTemplate();
            row.setTemplateKey(key);
            row.setName(PromptChecks.requiredText(req.getName(), 100, "name"));
            row.setPurpose(purpose);
            row.setLocale(locale);
            row.setProtocol(PromptChecks.optionalText(req.getProtocol(), 32, "protocol"));
            row.setRemark(PromptChecks.optionalText(req.getRemark(), 512, "remark"));
            row.setVersion(next);
            row.setStatus(PromptPublishStatus.DRAFT.getCode());
            row.setIsActive(0);
            Long id = templates.insert(row, operatorId);
            segments.replaceAll(id, segs);
            operLogWriter.write("prompt", "l2_draft", "prompt_template", String.valueOf(id),
                    "L2 新建草稿 key=" + key + " v" + next);
            return get(id);
        }

        PromptTemplate existing = require(req.getId());
        PromptChecks.requireDraft(existing.getStatus());
        existing.setName(PromptChecks.requiredText(req.getName(), 100, "name"));
        existing.setPurpose(PromptChecks.purpose(req.getPurpose()));
        existing.setProtocol(PromptChecks.optionalText(req.getProtocol(), 32, "protocol"));
        existing.setRemark(PromptChecks.optionalText(req.getRemark(), 512, "remark"));
        // templateKey / locale / version 随版本行固定，草稿更新不改键
        templates.updateDraft(existing, operatorId);
        segments.replaceAll(existing.getId(), segs);
        operLogWriter.write("prompt", "l2_draft", "prompt_template", String.valueOf(existing.getId()),
                "L2 更新草稿 key=" + existing.getTemplateKey() + " v" + existing.getVersion());
        return get(existing.getId());
    }

    /**
     * 发布草稿并切换生效。
     * 生效口径：同一 purpose + locale 至多一条 is_active，因此先按用途熄灭其它生效行。
     *
     * @param id 草稿主键
     * @return 详情
     */
    @Transactional
    public PromptTemplateResp publish(Long id) {
        PromptTemplate existing = require(id);
        PromptChecks.requireDraftForPublish(existing.getStatus());
        Long operatorId = operatorContext.requireUserId();
        // 先按 key 熄灭同键历史，再按用途熄灭同用途其它键，两步都做保证唯一生效
        templates.clearActive(existing.getTemplateKey(), existing.getLocale(), operatorId);
        templates.clearActiveByPurpose(existing.getPurpose(), existing.getLocale(), operatorId);
        templates.markPublishedActive(id, operatorId);
        // 生效切换后必须清缓存：按键与按用途两个引用都可能变，两者都失效。
        // 失效在事务提交后执行，避免并发读者把提交前的旧引用写回缓存。
        cacheInvalidator.evictPurposeRef(existing.getPurpose(), existing.getLocale());
        cacheInvalidator.evictTemplateRef(existing.getTemplateKey(), existing.getLocale());
        operLogWriter.write("prompt", "l2_publish", "prompt_template", String.valueOf(id),
                "L2 发布 key=" + existing.getTemplateKey() + " v" + existing.getVersion());
        return get(id);
    }

    /**
     * @param id 主键
     * @return 未删除模板
     */
    private PromptTemplate require(Long id) {
        return templates.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "提示词模板不存在"));
    }

    /**
     * 校验「同 purpose+locale 只允许一个 template_key」。
     * <p>
     * 管理端左栏是固定用途槽位，一个用途一张卡；若放任再造同用途第二个键，
     * 左栏会出现无法并存的幽灵模板（catalog 只展示生效/最新那个）。
     * 已有键时只允许沿用该键继续开版本，键不一致直接拒绝。
     *
     * @param purpose 用途码（已规范）
     * @param locale  语言（已规范）
     * @param key     本次落库的业务键（已规范）
     * @throws BizException 该用途已有其它业务键
     */
    private void assertSingleKeyPerPurpose(String purpose, String locale, String key) {
        PromptTemplateQuery query = new PromptTemplateQuery();
        query.setCurrent(1);
        query.setSize(100);
        query.setPurpose(purpose);
        query.setLocale(locale);
        // 已有键里排除本次这个（首次创建时集合为空），剩下的任何一个都说明该用途被占了
        String occupied = templates.page(query).getRecords().stream()
                .map(PromptTemplate::getTemplateKey)
                .filter(existing -> existing != null && !existing.isBlank() && !existing.equals(key))
                .findFirst()
                .orElse(null);
        if (occupied != null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "该用途已绑定模板键 " + occupied + "，请沿用该键开新版本，不要新建第二个键");
        }
    }

    private List<PromptSegment> toSegments(List<PromptSegmentReq> reqs) {
        List<PromptSegment> list = new ArrayList<>();
        if (reqs == null) {
            return list;
        }
        int index = 0;
        for (PromptSegmentReq req : reqs) {
            PromptSegment segment = new PromptSegment();
            segment.setSegKey(PromptChecks.requiredText(req.getSegKey(), 64, "segKey"));
            segment.setDisplayName(PromptChecks.requiredText(req.getDisplayName(), 64, "displayName"));
            segment.setSortNo(req.getSortNo() == null ? index : req.getSortNo());
            segment.setMutable(PromptChecks.flag01(req.getMutable(), 0, "mutable"));
            segment.setRequired(PromptChecks.flag01(req.getRequired(), 1, "required"));
            segment.setContent(PromptChecks.requiredContent(req.getContent(), "段落正文"));
            list.add(segment);
            index++;
        }
        return list;
    }

    private PromptTemplateResp toResp(PromptTemplate domain, List<PromptSegment> segs) {
        PromptTemplateResp resp = new PromptTemplateResp();
        resp.setId(domain.getId());
        resp.setTemplateKey(domain.getTemplateKey());
        resp.setName(domain.getName());
        resp.setPurpose(domain.getPurpose());
        resp.setLocale(domain.getLocale());
        resp.setProtocol(domain.getProtocol());
        resp.setVersion(domain.getVersion());
        resp.setStatus(domain.getStatus());
        resp.setIsActive(domain.getIsActive());
        resp.setRemark(domain.getRemark());
        resp.setCreateBy(domain.getCreateBy());
        resp.setUpdateBy(domain.getUpdateBy());
        resp.setCreateTime(domain.getCreateTime());
        resp.setUpdateTime(domain.getUpdateTime());
        List<PromptSegmentResp> segmentResps = new ArrayList<>();
        for (PromptSegment seg : segs) {
            PromptSegmentResp item = new PromptSegmentResp();
            item.setId(seg.getId());
            item.setTemplateId(seg.getTemplateId());
            item.setSegKey(seg.getSegKey());
            item.setDisplayName(seg.getDisplayName());
            item.setSortNo(seg.getSortNo());
            item.setMutable(seg.getMutable());
            item.setRequired(seg.getRequired());
            item.setContent(seg.getContent());
            item.setCreateTime(seg.getCreateTime());
            item.setUpdateTime(seg.getUpdateTime());
            segmentResps.add(item);
        }
        resp.setSegments(segmentResps);
        return resp;
    }
}
