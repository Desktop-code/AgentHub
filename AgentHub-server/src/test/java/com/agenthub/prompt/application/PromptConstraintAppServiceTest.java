package com.agenthub.prompt.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.prompt.api.dto.PromptConstraintDraftReq;
import com.agenthub.prompt.api.dto.PromptConstraintResp;
import com.agenthub.prompt.domain.enums.PromptPublishStatus;
import com.agenthub.prompt.domain.model.PromptConstraint;
import com.agenthub.prompt.domain.repository.PromptConstraintRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
 * L1 发布切 active 与非法状态。所属域：prompt。禁真模型出站（本测无 HTTP）。
 */
@ExtendWith(MockitoExtension.class)
class PromptConstraintAppServiceTest {

    @Mock
    private PromptConstraintRepository constraints;
    @Mock
    private OperatorContext operatorContext;
    @Mock
    private OperLogWriter operLogWriter;
    @Mock
    private com.agenthub.prompt.domain.repository.PromptCacheInvalidator cacheInvalidator;

    private PromptConstraintAppService service;

    @BeforeEach
    void setUp() {
        service = new PromptConstraintAppService(constraints, operatorContext, operLogWriter, cacheInvalidator);
    }

    @Test
    void publishSwitchesActiveForLocale() {
        PromptConstraint draft = draft(11L, "zh-CN", 2);
        when(operatorContext.requireUserId()).thenReturn(7L);
        when(constraints.findById(11L)).thenReturn(Optional.of(draft), Optional.of(published(11L, "zh-CN", 2)));

        PromptConstraintResp resp = service.publish(11L);

        verify(constraints).clearActive("zh-CN", 7L);
        verify(constraints).markPublishedActive(11L, 7L);
        assertEquals(1, resp.getIsActive());
        assertEquals(PromptPublishStatus.PUBLISHED.getCode(), resp.getStatus());
    }

    @Test
    void publishRejectsAlreadyPublished() {
        PromptConstraint row = published(3L, "zh-CN", 1);
        when(constraints.findById(3L)).thenReturn(Optional.of(row));

        BizException ex = assertThrows(BizException.class, () -> service.publish(3L));
        assertTrue(ex.getMessage().contains("草稿"));
        verify(constraints, never()).clearActive(any(), anyLong());
        verify(constraints, never()).markPublishedActive(anyLong(), anyLong());
    }

    @Test
    void saveDraftRejectsPublishedRow() {
        PromptConstraint row = published(5L, "zh-CN", 1);
        when(constraints.findById(5L)).thenReturn(Optional.of(row));

        PromptConstraintDraftReq req = new PromptConstraintDraftReq();
        req.setId(5L);
        req.setContent("新正文");

        BizException ex = assertThrows(BizException.class, () -> service.saveDraft(req));
        assertTrue(ex.getMessage().contains("草稿"));
        verify(constraints, never()).updateDraftContent(any(), anyLong());
    }

    @Test
    void saveDraftCreatesNextVersion() {
        when(constraints.maxVersion("zh-CN")).thenReturn(1);
        when(operatorContext.requireUserId()).thenReturn(9L);
        when(constraints.insert(any(), eq(9L))).thenReturn(20L);
        PromptConstraint saved = draft(20L, "zh-CN", 2);
        when(constraints.findById(20L)).thenReturn(Optional.of(saved));

        PromptConstraintDraftReq req = new PromptConstraintDraftReq();
        req.setLocale("zh-CN");
        req.setContent("平台红线");

        PromptConstraintResp resp = service.saveDraft(req);

        ArgumentCaptor<PromptConstraint> captor = ArgumentCaptor.forClass(PromptConstraint.class);
        verify(constraints).insert(captor.capture(), eq(9L));
        assertEquals(2, captor.getValue().getVersion());
        assertEquals(PromptPublishStatus.DRAFT.getCode(), captor.getValue().getStatus());
        assertEquals(0, captor.getValue().getIsActive());
        assertEquals(20L, resp.getId());
    }

    private static PromptConstraint draft(Long id, String locale, int version) {
        PromptConstraint row = new PromptConstraint();
        row.setId(id);
        row.setLocale(locale);
        row.setContent("draft");
        row.setVersion(version);
        row.setStatus(PromptPublishStatus.DRAFT.getCode());
        row.setIsActive(0);
        return row;
    }

    private static PromptConstraint published(Long id, String locale, int version) {
        PromptConstraint row = draft(id, locale, version);
        row.setStatus(PromptPublishStatus.PUBLISHED.getCode());
        row.setIsActive(1);
        row.setContent("published");
        return row;
    }
}
