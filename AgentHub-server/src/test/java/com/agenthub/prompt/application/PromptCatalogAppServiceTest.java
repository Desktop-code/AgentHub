package com.agenthub.prompt.application;

import com.agenthub.prompt.api.dto.PromptCatalogResp;
import com.agenthub.prompt.api.dto.PromptCatalogTemplateResp;
import com.agenthub.prompt.domain.model.PromptConstraint;
import com.agenthub.prompt.domain.model.PromptTemplate;
import com.agenthub.prompt.domain.repository.PromptConstraintRepository;
import com.agenthub.prompt.domain.repository.PromptTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

/**
 * 左栏目录聚合：L1 摘要透传、L2 折叠成**固定用途槽位**。所属域：prompt。
 * 纯内存聚合，无 HTTP、无模型出站。
 */
@ExtendWith(MockitoExtension.class)
class PromptCatalogAppServiceTest {

    @Mock
    private PromptConstraintRepository constraints;
    @Mock
    private PromptTemplateRepository templates;

    private PromptCatalogAppService service;

    @BeforeEach
    void setUp() {
        service = new PromptCatalogAppService(constraints, templates);
    }

    @Test
    void catalogDefaultsLocaleAndCarriesConstraintSummary() {
        when(constraints.listByLocale("zh-CN")).thenReturn(List.of(constraint(9L, 2, 1, 1), constraint(8L, 1, 1, 0)));
        when(templates.listByLocale("zh-CN")).thenReturn(List.of());

        PromptCatalogResp resp = service.catalog(null);

        assertEquals("zh-CN", resp.getLocale());
        assertEquals(2, resp.getConstraints().size());
        assertEquals(9L, resp.getConstraints().get(0).getId());
        assertEquals(1, resp.getConstraints().get(0).getStatus());
    }

    @Test
    void catalogAlwaysReturnsEveryPurposeSlotInEnumOrder() {
        when(constraints.listByLocale("zh-CN")).thenReturn(List.of());
        when(templates.listByLocale("zh-CN")).thenReturn(List.of());

        PromptCatalogResp resp = service.catalog("zh-CN");

        // 左栏是固定槽位：CHAT / ROUTER / REWRITE / SUMMARY / MEMORY 恒各一条
        assertEquals(List.of("CHAT", "ROUTER", "REWRITE", "SUMMARY", "MEMORY"),
                resp.getTemplates().stream().map(PromptCatalogTemplateResp::getPurpose).toList());
    }

    @Test
    void emptySlotKeepsConventionKeyAndZeroCount() {
        when(constraints.listByLocale("zh-CN")).thenReturn(List.of());
        when(templates.listByLocale("zh-CN")).thenReturn(List.of());

        PromptCatalogResp resp = service.catalog("zh-CN");

        PromptCatalogTemplateResp chat = slotOf(resp, "CHAT");
        // 空槽也用用途名作为约定键，前端新建首稿可直接沿用，不必猜命名
        assertEquals("CHAT", chat.getTemplateKey());
        assertEquals(0, chat.getVersionCount());
        assertNull(chat.getLatestId());
        assertNull(chat.getActiveId());
    }

    @Test
    void slotCollapsesVersionsOfTheBoundKey() {
        when(constraints.listByLocale("zh-CN")).thenReturn(List.of());
        when(templates.listByLocale("zh-CN")).thenReturn(List.of(
                template(30L, "CHAT_DEFAULT", "CHAT", 3, 0, 0),
                template(20L, "CHAT_DEFAULT", "CHAT", 2, 1, 1),
                template(10L, "CHAT_DEFAULT", "CHAT", 1, 1, 0)
        ));

        PromptCatalogResp resp = service.catalog("zh-CN");

        PromptCatalogTemplateResp chat = slotOf(resp, "CHAT");
        assertEquals("CHAT_DEFAULT", chat.getTemplateKey());
        assertEquals(3, chat.getVersionCount());
        assertEquals(30L, chat.getLatestId());
        assertEquals(3, chat.getLatestVersion());
        assertEquals(20L, chat.getActiveId());
        assertEquals(2, chat.getActiveVersion());
    }

    @Test
    void slotBindsActiveKeyWhenSamePurposeHasMultipleKeys() {
        when(constraints.listByLocale("zh-CN")).thenReturn(List.of());
        // 存量脏数据：同用途两个 key，只有 _2 生效 → 左栏必须只出这一张，且绑生效 key
        when(templates.listByLocale("zh-CN")).thenReturn(List.of(
                template(50L, "CHAT_DEFAULT", "CHAT", 1, 1, 0),
                template(60L, "CHAT_DEFAULT_2", "CHAT", 2, 1, 1)
        ));

        PromptCatalogResp resp = service.catalog("zh-CN");

        // 关键断言：同用途只出现一张卡
        assertEquals(1, resp.getTemplates().stream().filter(item -> "CHAT".equals(item.getPurpose())).count());
        PromptCatalogTemplateResp chat = slotOf(resp, "CHAT");
        assertEquals("CHAT_DEFAULT_2", chat.getTemplateKey());
        assertEquals(60L, chat.getActiveId());
        // 版本数只算绑定 key 的，不把另一个 key 的历史混进来
        assertEquals(1, chat.getVersionCount());
    }

    @Test
    void slotFallsBackToHighestVersionKeyWithoutActive() {
        when(constraints.listByLocale("zh-CN")).thenReturn(List.of());
        when(templates.listByLocale("zh-CN")).thenReturn(List.of(
                template(70L, "ROUTER_A", "ROUTER", 1, 0, 0),
                template(80L, "ROUTER_B", "ROUTER", 5, 0, 0)
        ));

        PromptCatalogResp resp = service.catalog("zh-CN");

        PromptCatalogTemplateResp router = slotOf(resp, "ROUTER");
        assertEquals("ROUTER_B", router.getTemplateKey());
        assertEquals(5, router.getLatestVersion());
        assertNull(router.getActiveId());
    }

    @Test
    void slotIgnoresActiveFlagWithoutPublishedStatus() {
        when(constraints.listByLocale("zh-CN")).thenReturn(List.of());
        // is_active=1 但仍是草稿：不算生效，避免脏数据把标识打乱
        when(templates.listByLocale("zh-CN")).thenReturn(List.of(template(50L, "SUMMARY_DEFAULT", "SUMMARY", 1, 0, 1)));

        PromptCatalogResp resp = service.catalog("zh-CN");

        PromptCatalogTemplateResp summary = slotOf(resp, "SUMMARY");
        assertNull(summary.getActiveId());
        assertEquals(1, summary.getVersionCount());
    }

    /**
     * 按用途取槽位，顺带断言该用途存在且唯一。
     *
     * @param resp   目录
     * @param purpose 用途码
     * @return 该用途槽位
     */
    private PromptCatalogTemplateResp slotOf(PromptCatalogResp resp, String purpose) {
        Map<String, PromptCatalogTemplateResp> byPurpose = resp.getTemplates().stream()
                .collect(Collectors.toMap(PromptCatalogTemplateResp::getPurpose, Function.identity()));
        PromptCatalogTemplateResp slot = byPurpose.get(purpose);
        assertNotNull(slot, "缺少槽位: " + purpose);
        return slot;
    }

    private static PromptConstraint constraint(Long id, int version, int status, int isActive) {
        PromptConstraint row = new PromptConstraint();
        row.setId(id);
        row.setLocale("zh-CN");
        row.setContent("正文");
        row.setVersion(version);
        row.setStatus(status);
        row.setIsActive(isActive);
        row.setUpdateTime(LocalDateTime.of(2026, 10, 31, 10, 0));
        return row;
    }

    private static PromptTemplate template(Long id, String key, String purpose, int version, int status, int isActive) {
        PromptTemplate row = new PromptTemplate();
        row.setId(id);
        row.setTemplateKey(key);
        row.setName("名-" + key);
        row.setPurpose(purpose);
        row.setLocale("zh-CN");
        row.setVersion(version);
        row.setStatus(status);
        row.setIsActive(isActive);
        row.setUpdateTime(LocalDateTime.of(2026, 10, 31, 10, version));
        return row;
    }
}
