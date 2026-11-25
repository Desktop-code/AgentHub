package com.agenthub.skill.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.skill.api.dto.SkillVersionDetailResp;
import com.agenthub.skill.api.dto.SkillVersionDraftReq;
import com.agenthub.skill.api.dto.SkillVersionSaveReq;
import com.agenthub.skill.domain.enums.SkillPublishStatus;
import com.agenthub.skill.domain.model.SkillDef;
import com.agenthub.skill.domain.model.SkillVersion;
import com.agenthub.skill.domain.repository.SkillDefRepository;
import com.agenthub.skill.domain.repository.SkillVersionRepository;
import com.agenthub.spi.cache.AgentDictCacheInvalidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Skill 版本状态机单测。所属域：skill。
 *
 * <p>覆盖本刀最关键的 4 条口径：
 * <ol>
 *   <li>发布：先清旧生效位再置新，并把生效号/简介镜像回 skill_def；</li>
 *   <li>已发布版本正文只读，保存草稿必须被拒；</li>
 *   <li>设为生效只接受已发布行，草稿不能越过发布直接生效；</li>
 *   <li>同 Skill 至多一条草稿；新建草稿取 max+1 号并保持草稿态。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class SkillVersionAppServiceTest {

    private static final Long SKILL_ID = 1L;
    private static final Long OPERATOR_ID = 7L;

    @Mock
    private SkillDefRepository skills;
    @Mock
    private SkillVersionRepository versions;
    @Mock
    private OperatorContext operatorContext;
    @Mock
    private OperLogWriter operLogWriter;

    private SkillVersionAppService service;

    @BeforeEach
    void setUp() {
        service = new SkillVersionAppService(skills, versions, new SkillAssembler(), operatorContext, operLogWriter,
                AgentDictCacheInvalidator.NOOP);
    }

    @Test
    void publishClearsOldActiveThenActivatesAndMirrorsDef() {
        SkillDef skill = skill("pdf_extract");
        SkillVersion draft = version(11L, 2, SkillPublishStatus.DRAFT.getCode(), 0);
        when(skills.findById(SKILL_ID)).thenReturn(Optional.of(skill));
        when(versions.findById(11L)).thenReturn(Optional.of(draft));
        lenient().when(operatorContext.requireUserId()).thenReturn(OPERATOR_ID);
        // 模拟发布落库：状态改为已发布并生效，后续 get 读到新状态
        when(versions.markPublished(eq(11L), eq(OPERATOR_ID), any())).thenAnswer(invocation -> {
            draft.setPublishStatus(SkillPublishStatus.PUBLISHED.getCode());
            draft.setIsActive(1);
            return true;
        });

        SkillVersionDetailResp resp = service.publish(SKILL_ID, 11L);

        InOrder order = inOrder(versions);
        order.verify(versions).clearActive(SKILL_ID);
        order.verify(versions).activate(11L);
        verify(skills).updateActiveMirror(SKILL_ID, "2", "简介", OPERATOR_ID);
        assertEquals(SkillPublishStatus.PUBLISHED.getCode(), resp.getPublishStatus());
        assertEquals(1, resp.getIsActive(), "发布后必须唯一生效");
    }

    @Test
    void savingPublishedVersionIsRejected() {
        when(versions.findById(9L)).thenReturn(Optional.of(
                version(9L, 1, SkillPublishStatus.PUBLISHED.getCode(), 1)));

        SkillVersionSaveReq req = new SkillVersionSaveReq();
        req.setContentMd("改线上正文");

        assertThrows(BizException.class, () -> service.saveDraft(SKILL_ID, 9L, req));
        verify(versions, never()).updateDraft(any());
    }

    @Test
    void draftCannotBeActivatedWithoutPublish() {
        when(skills.findById(SKILL_ID)).thenReturn(Optional.of(skill("pdf_extract")));
        when(versions.findById(12L)).thenReturn(Optional.of(
                version(12L, 3, SkillPublishStatus.DRAFT.getCode(), 0)));

        assertThrows(BizException.class, () -> service.activate(SKILL_ID, 12L));
        verify(versions, never()).activate(anyLong());
    }

    @Test
    void draftCanBeDeletedButPublishedCannot() {
        when(skills.findById(SKILL_ID)).thenReturn(Optional.of(skill("pdf_extract")));
        when(versions.findById(12L)).thenReturn(Optional.of(
                version(12L, 3, SkillPublishStatus.DRAFT.getCode(), 0)));
        when(versions.deleteDraft(12L)).thenReturn(true);

        service.deleteDraft(SKILL_ID, 12L);
        verify(versions).deleteDraft(12L);

        // 已发布历史行不可删：服务层直接拒绝，不会调到仓储
        when(versions.findById(9L)).thenReturn(Optional.of(
                version(9L, 1, SkillPublishStatus.PUBLISHED.getCode(), 1)));
        assertThrows(BizException.class, () -> service.deleteDraft(SKILL_ID, 9L));
        verify(versions, never()).deleteDraft(9L);
    }

    @Test
    void secondDraftIsRejected() {
        when(skills.findById(SKILL_ID)).thenReturn(Optional.of(skill("pdf_extract")));
        when(versions.findDraft(SKILL_ID)).thenReturn(Optional.of(
                version(12L, 3, SkillPublishStatus.DRAFT.getCode(), 0)));

        SkillVersionDraftReq req = new SkillVersionDraftReq();
        assertThrows(BizException.class, () -> service.createDraft(SKILL_ID, req));
        verify(versions, never()).insert(any());
    }

    @Test
    void newDraftTakesNextVersionNoAndStaysDraft() {
        when(skills.findById(SKILL_ID)).thenReturn(Optional.of(skill("pdf_extract")));
        when(versions.findDraft(SKILL_ID)).thenReturn(Optional.empty());
        when(versions.nextVersionNo(SKILL_ID)).thenReturn(3);
        when(versions.insert(any())).thenReturn(33L);
        when(versions.findById(33L)).thenReturn(Optional.of(
                version(33L, 3, SkillPublishStatus.DRAFT.getCode(), 0)));

        SkillVersionDraftReq req = new SkillVersionDraftReq();
        req.setContentMd("# 草稿正文");

        SkillVersionDetailResp resp = service.createDraft(SKILL_ID, req);

        ArgumentCaptor<SkillVersion> captor = ArgumentCaptor.forClass(SkillVersion.class);
        verify(versions).insert(captor.capture());
        assertEquals(3, captor.getValue().getVersionNo(), "草稿创建即占 max+1 号");
        assertEquals(SkillPublishStatus.DRAFT.getCode(), resp.getPublishStatus(), "新草稿不得自动发布");
    }

    /**
     * @param name 名称
     * @return 最小可用的 Skill 定义
     */
    private static SkillDef skill(String name) {
        SkillDef skill = new SkillDef();
        skill.setId(SKILL_ID);
        skill.setName(name);
        skill.setTitle("PDF 抽取");
        return skill;
    }

    /**
     * @param id     版本主键
     * @param no     版本号
     * @param status 0 草稿 / 1 已发布
     * @param active 1 生效
     * @return 版本行
     */
    private static SkillVersion version(Long id, int no, int status, int active) {
        SkillVersion version = new SkillVersion();
        version.setId(id);
        version.setSkillId(SKILL_ID);
        version.setVersionNo(no);
        version.setDescription("简介");
        version.setContentMd("# 正文");
        version.setPublishStatus(status);
        version.setIsActive(active);
        return version;
    }
}
