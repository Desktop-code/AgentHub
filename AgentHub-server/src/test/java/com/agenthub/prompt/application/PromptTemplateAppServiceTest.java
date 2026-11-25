package com.agenthub.prompt.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.prompt.api.dto.PromptSegmentReq;
import com.agenthub.prompt.api.dto.PromptTemplateDraftReq;
import com.agenthub.prompt.api.dto.PromptTemplateResp;
import com.agenthub.prompt.domain.enums.PromptPublishStatus;
import com.agenthub.prompt.domain.model.PromptTemplate;
import com.agenthub.prompt.domain.repository.PromptSegmentRepository;
import com.agenthub.prompt.domain.repository.PromptTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L2 发布切 active、非法状态、段落同步。所属域：prompt。
 */
@ExtendWith(MockitoExtension.class)
class PromptTemplateAppServiceTest {

    @Mock
    private PromptTemplateRepository templates;
    @Mock
    private PromptSegmentRepository segments;
    @Mock
    private OperatorContext operatorContext;
    @Mock
    private OperLogWriter operLogWriter;
    @Mock
    private com.agenthub.prompt.domain.repository.PromptCacheInvalidator cacheInvalidator;

    private PromptTemplateAppService service;

    @BeforeEach
    void setUp() {
        service = new PromptTemplateAppService(templates, segments, operatorContext, operLogWriter,
                cacheInvalidator);
    }

    @Test
    void publishClearsActiveThenMarks() {
        PromptTemplate draft = draft(8L, "CHAT_DEFAULT", "zh-CN", 3);
        when(templates.findById(8L)).thenReturn(Optional.of(draft), Optional.of(published(8L, "CHAT_DEFAULT", "zh-CN", 3)));
        when(operatorContext.requireUserId()).thenReturn(1L);
        when(segments.listByTemplateId(8L)).thenReturn(List.of());

        PromptTemplateResp resp = service.publish(8L);

        // 同键与同用途两步熄灭都要发生，保证 purpose+locale 唯一生效
        verify(templates).clearActive("CHAT_DEFAULT", "zh-CN", 1L);
        verify(templates).clearActiveByPurpose("CHAT", "zh-CN", 1L);
        verify(templates).markPublishedActive(8L, 1L);
        assertEquals(1, resp.getIsActive());
    }

    @Test
    void publishRejectsNonDraft() {
        when(templates.findById(2L)).thenReturn(Optional.of(published(2L, "CHAT_DEFAULT", "zh-CN", 1)));

        BizException ex = assertThrows(BizException.class, () -> service.publish(2L));
        assertTrue(ex.getMessage().contains("草稿"));
        verify(templates, never()).clearActive(any(), any(), anyLong());
        verify(templates, never()).clearActiveByPurpose(any(), any(), anyLong());
    }

    @Test
    void saveDraftRejectsPublished() {
        when(templates.findById(4L)).thenReturn(Optional.of(published(4L, "CHAT_DEFAULT", "zh-CN", 1)));

        PromptTemplateDraftReq req = new PromptTemplateDraftReq();
        req.setId(4L);
        req.setName("对话默认");
        req.setPurpose("CHAT");
        req.setSegments(List.of(segment("role", "角色设定")));

        BizException ex = assertThrows(BizException.class, () -> service.saveDraft(req));
        assertTrue(ex.getMessage().contains("草稿"));
        verify(segments, never()).replaceAll(anyLong(), any());
    }

    @Test
    void saveDraftCreatesAndReplacesSegments() {
        when(templates.maxVersion("CHAT_DEFAULT", "zh-CN")).thenReturn(0);
        when(operatorContext.requireUserId()).thenReturn(3L);
        when(templates.page(any())).thenReturn(PageResult.of(0, 1, 100, List.of()));
        when(templates.insert(any(), eq(3L))).thenReturn(50L);
        PromptTemplate saved = draft(50L, "CHAT_DEFAULT", "zh-CN", 1);
        when(templates.findById(50L)).thenReturn(Optional.of(saved));
        when(segments.listByTemplateId(50L)).thenReturn(List.of());

        PromptTemplateDraftReq req = new PromptTemplateDraftReq();
        req.setTemplateKey("chat_default");
        req.setName("对话默认");
        req.setPurpose("CHAT");
        req.setSegments(List.of(segment("role", "角色设定")));

        PromptTemplateResp resp = service.saveDraft(req);

        verify(segments).replaceAll(eq(50L), any());
        assertEquals(50L, resp.getId());
        assertEquals("CHAT_DEFAULT", resp.getTemplateKey());
    }

    @Test
    void saveDraftRejectsDuplicateSegKey() {
        PromptTemplateDraftReq req = new PromptTemplateDraftReq();
        req.setTemplateKey("CHAT_DEFAULT");
        req.setName("对话默认");
        req.setPurpose("CHAT");
        req.setSegments(List.of(segment("role", "A"), segment("role", "B")));

        BizException ex = assertThrows(BizException.class, () -> service.saveDraft(req));
        assertTrue(ex.getMessage().contains("重复"));
        verify(templates, never()).insert(any(), anyLong());
    }

    @Test
    void saveDraftRejectsSecondKeyForSamePurpose() {
        // 该用途已绑定 CHAT_DEFAULT，前端/接口都不允许再建 CHAT_DEFAULT_2
        when(operatorContext.requireUserId()).thenReturn(3L);
        when(templates.page(any())).thenReturn(
                PageResult.of(1, 1, 100, List.of(draft(9L, "CHAT_DEFAULT", "zh-CN", 1))));

        PromptTemplateDraftReq req = new PromptTemplateDraftReq();
        req.setTemplateKey("CHAT_DEFAULT_2");
        req.setName("对话默认二号");
        req.setPurpose("CHAT");
        req.setSegments(List.of(segment("role", "角色设定")));

        BizException ex = assertThrows(BizException.class, () -> service.saveDraft(req));
        assertTrue(ex.getMessage().contains("CHAT_DEFAULT"));
        verify(templates, never()).insert(any(), anyLong());
    }

    @Test
    void saveDraftRejectsPlatformLockedMemoryContentChange() {
        PromptTemplate memoryDraft = draft(60L, "MEMORY", "zh-CN", 1);
        memoryDraft.setPurpose("MEMORY");
        when(templates.findById(60L)).thenReturn(Optional.of(memoryDraft));
        when(operatorContext.requireUserId()).thenReturn(3L);
        when(segments.listByTemplateId(60L)).thenReturn(List.of(
                lockedSegment("core_contract", "抽取契约", "平台锁定契约"),
                lockedSegment("output_format", "输出格式", "平台锁定格式")));

        PromptTemplateDraftReq req = new PromptTemplateDraftReq();
        req.setId(60L);
        req.setName("记忆抽取模板");
        req.setPurpose("MEMORY");
        req.setSegments(List.of(
                segmentWithContent("core_contract", "抽取契约", "想把契约改掉"),
                segmentWithContent("output_format", "输出格式", "平台锁定格式")));

        BizException ex = assertThrows(BizException.class, () -> service.saveDraft(req));
        assertTrue(ex.getMessage().contains("平台锁定段落不可修改"), ex.getMessage());
        verify(segments, never()).replaceAll(anyLong(), any());
    }

    @Test
    void saveDraftAllowsNextVersionOfBoundKey() {
        // 沿用该用途已绑定的键开新版本，必须放行
        when(templates.maxVersion("CHAT_DEFAULT", "zh-CN")).thenReturn(1);
        when(operatorContext.requireUserId()).thenReturn(3L);
        when(templates.page(any())).thenReturn(
                PageResult.of(1, 1, 100, List.of(draft(9L, "CHAT_DEFAULT", "zh-CN", 1))));
        when(templates.insert(any(), eq(3L))).thenReturn(51L);
        when(templates.findById(51L)).thenReturn(Optional.of(draft(51L, "CHAT_DEFAULT", "zh-CN", 2)));
        when(segments.listByTemplateId(51L)).thenReturn(List.of());

        PromptTemplateDraftReq req = new PromptTemplateDraftReq();
        req.setTemplateKey("CHAT_DEFAULT");
        req.setName("对话默认");
        req.setPurpose("CHAT");
        req.setSegments(List.of(segment("role", "角色设定")));

        PromptTemplateResp resp = service.saveDraft(req);

        assertEquals(51L, resp.getId());
        assertEquals(2, resp.getVersion());
    }

    private static PromptSegmentReq segmentWithContent(String key, String name, String content) {
        PromptSegmentReq req = segment(key, name);
        req.setContent(content);
        return req;
    }

    private static com.agenthub.prompt.domain.model.PromptSegment lockedSegment(String key, String name,
                                                                                 String content) {
        com.agenthub.prompt.domain.model.PromptSegment segment = new com.agenthub.prompt.domain.model.PromptSegment();
        segment.setSegKey(key);
        segment.setDisplayName(name);
        segment.setSortNo(10);
        segment.setMutable(0);
        segment.setRequired(1);
        segment.setContent(content);
        return segment;
    }

    private static PromptSegmentReq segment(String key, String name) {
        PromptSegmentReq req = new PromptSegmentReq();
        req.setSegKey(key);
        req.setDisplayName(name);
        req.setContent("正文-" + key);
        req.setMutable(0);
        req.setRequired(1);
        req.setSortNo(0);
        return req;
    }

    private static PromptTemplate draft(Long id, String key, String locale, int version) {
        PromptTemplate row = new PromptTemplate();
        row.setId(id);
        row.setTemplateKey(key);
        row.setName("名");
        row.setPurpose("CHAT");
        row.setLocale(locale);
        row.setVersion(version);
        row.setStatus(PromptPublishStatus.DRAFT.getCode());
        row.setIsActive(0);
        return row;
    }

    private static PromptTemplate published(Long id, String key, String locale, int version) {
        PromptTemplate row = draft(id, key, locale, version);
        row.setStatus(PromptPublishStatus.PUBLISHED.getCode());
        row.setIsActive(1);
        return row;
    }
}
