package com.agenthub.prompt.application;

import com.agenthub.agent.domain.model.AgentPromptBinding;
import com.agenthub.agent.domain.model.AgentPromptPreviewResult;
import com.agenthub.agent.domain.model.AgentPromptTemplateOption;
import com.agenthub.agent.domain.model.AgentPromptTemplateView;
import com.agenthub.common.error.BizException;
import com.agenthub.prompt.PromptTestFixtures;
import com.agenthub.prompt.domain.model.PromptSegment;
import com.agenthub.prompt.domain.model.PromptTemplate;
import com.agenthub.prompt.domain.repository.PromptSegmentRepository;
import com.agenthub.prompt.domain.repository.PromptTemplateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3 保存硬闸与预览路径的单测。所属域：prompt（test）。
 * <p>
 * 覆盖验收点：
 * <ol>
 *   <li>core 段与未知段拒绝保存；</li>
 *   <li>版本必须钉死且与模板行一致；</li>
 *   <li>用途不符（CHAT 挂 ROUTER 模板）拒绝；</li>
 *   <li>预览走 PromptAssembler，正文含 L1 与 L3 覆盖与追加区，且不触达任何模型。</li>
 * </ol>
 * 全程内存快照，不连库、不连 Redis、不出站。
 */
class AgentPromptBindingServiceTest {

    /** 与种子同形的 L2 结构：core_contract（锁定）+ role_tone（可覆盖） */
    private static final long CHAT_TEMPLATE_ID = 1L;
    private static final long ROUTER_TEMPLATE_ID = 2L;

    private final FakeTemplates templates = new FakeTemplates();
    private final FakeSegments segments = new FakeSegments();
    private AgentPromptBindingService service;

    @BeforeEach
    void setUp() {
        // 预览用内存快照：L1 取自种子资源，五个用途各一条，与生产同构
        service = new AgentPromptBindingService(templates, segments,
                PromptTestFixtures.assembler(PromptTestFixtures.snapshots()),
                new ObjectMapper(), null);
    }

    /**
     * core 段禁止覆盖：这是 L3 的硬边界，必须报错而不是静默忽略。
     */
    @Test
    void coreSegmentKeyIsRejected() {
        Map<String, String> override = new LinkedHashMap<>();
        override.put("core_contract", "想把锁定段整段换掉");

        BizException ex = assertThrows(BizException.class,
                () -> service.normalize("CHAT", CHAT_TEMPLATE_ID, 1, override, null));
        assertTrue(ex.getMessage().contains("锁定段"), ex.getMessage());
    }

    /**
     * 未知段（模板升级后旧 key 残留）同样拒绝保存，提示清理。
     */
    @Test
    void unknownSegmentKeyIsRejected() {
        Map<String, String> override = Map.of("removed_segment", "旧版残留");

        BizException ex = assertThrows(BizException.class,
                () -> service.normalize("CHAT", CHAT_TEMPLATE_ID, 1, override, null));
        assertTrue(ex.getMessage().contains("不存在"), ex.getMessage());
    }

    /**
     * soft 段合法覆盖：落成 JSON，且空白值不写入（否则会整段抹掉 soft）。
     */
    @Test
    void softSegmentKeyIsPersistedAsJson() {
        Map<String, String> override = new LinkedHashMap<>();
        override.put("role_tone", "你是电商售后顾问，语气简洁");
        override.put("  ", "忽略：空键");
        override.put("core_contract", "   ");

        AgentPromptBinding binding = service.normalize("CHAT", CHAT_TEMPLATE_ID, 1, override, "追加一句");
        assertEquals(CHAT_TEMPLATE_ID, binding.getTemplateId());
        assertEquals(1, binding.getTemplateVersion());
        assertEquals("追加一句", binding.getPromptExtra());
        assertNotNull(binding.getOverrideJson());
        assertTrue(binding.getOverrideJson().contains("role_tone"));
        assertFalse(binding.getOverrideJson().contains("core_contract"));
    }

    /**
     * 未选模板却提交覆盖：拒绝，避免库里出现无法解释的残值。
     */
    @Test
    void overrideWithoutTemplateIsRejected() {
        BizException ex = assertThrows(BizException.class,
                () -> service.normalize("CHAT", null, null, Map.of("role_tone", "x"), null));
        assertTrue(ex.getMessage().contains("未选择"), ex.getMessage());
    }

    /**
     * 只给 id 不给版本：拒绝，禁止「只存 id 不钉 version」。
     */
    @Test
    void versionMustBePinned() {
        BizException ex = assertThrows(BizException.class,
                () -> service.normalize("CHAT", CHAT_TEMPLATE_ID, null, null, null));
        assertTrue(ex.getMessage().contains("钉死"), ex.getMessage());
    }

    /**
     * 钉死版本与模板行不一致：拒绝（前端升级确认前不应改版本）。
     */
    @Test
    void versionMismatchIsRejected() {
        BizException ex = assertThrows(BizException.class,
                () -> service.normalize("CHAT", CHAT_TEMPLATE_ID, 2, null, null));
        assertTrue(ex.getMessage().contains("版本不一致"), ex.getMessage());
    }

    /**
     * 用途不符：CHAT 配置不能挂 ROUTER 模板，否则运行时必然阻断。
     */
    @Test
    void purposeMismatchIsRejected() {
        BizException ex = assertThrows(BizException.class,
                () -> service.normalize("CHAT", ROUTER_TEMPLATE_ID, 1, null, null));
        assertTrue(ex.getMessage().contains("用途不匹配"), ex.getMessage());
    }

    /**
     * 未发布版本不可挂：钉死草稿等于绑了一个永不生效的版本。
     */
    @Test
    void draftTemplateIsRejected() {
        templates.markDraft(CHAT_TEMPLATE_ID);
        BizException ex = assertThrows(BizException.class,
                () -> service.normalize("CHAT", CHAT_TEMPLATE_ID, 1, null, null));
        assertTrue(ex.getMessage().contains("未发布"), ex.getMessage());
    }

    /**
     * 清空配置：模板与覆盖都为空，追加区仍可留（CHAT 的 append-only 区）。
     */
    @Test
    void emptyBindingKeepsOnlyExtra() {
        AgentPromptBinding binding = service.normalize("CHAT", null, null, null, "只追加不覆盖");
        assertNull(binding.getTemplateId());
        assertNull(binding.getTemplateVersion());
        assertNull(binding.getOverrideJson());
        assertEquals("只追加不覆盖", binding.getPromptExtra());
    }

    /**
     * 预览正文必须来自 Assembler：含 L1 种子与 L3 覆盖值，并带回版本统计。
     * 全程不触达模型 —— 本测试没有装配任何模型客户端，若走了模型会直接失败。
     */
    @Test
    void previewGoesThroughAssembler() {
        Map<String, String> override = Map.of("role_tone", "你是电商售后顾问");
        AgentPromptPreviewResult result = service.preview("CHAT", "zh-CN", CHAT_TEMPLATE_ID, 1,
                override, "另外请引用条款编号", "售后助手", "退货怎么走");

        // L1 来自种子资源：证明走的是真实组装链路而不是另拼一份
        assertTrue(result.getText().contains("【平台约束"), result.getText());
        assertTrue(result.getText().contains("你是电商售后顾问"), result.getText());
        assertTrue(result.getText().contains("另外请引用条款编号"), result.getText());
        // core 段由库文注入，未被覆盖
        assertTrue(result.getText().contains("先直接回答"), result.getText());
        assertEquals("CHAT", result.getPurpose());
        assertEquals(1, result.getTemplateVersion());
        assertEquals(1, result.getOverriddenSegmentCount());
        assertEquals(2, result.getSegmentCount());
    }

    /**
     * 预览同样受保存闸约束：拿 core key 预览不能「看起来成功」，必须与保存一致地失败。
     */
    @Test
    void previewRejectsIllegalKeysLikeSave() {
        BizException ex = assertThrows(BizException.class,
                () -> service.preview("CHAT", "zh-CN", CHAT_TEMPLATE_ID, 1,
                        Map.of("core_contract", "hack"), null, "a", null));
        assertTrue(ex.getMessage().contains("锁定段"), ex.getMessage());
    }

    /**
     * 未选模板不能预览：没有模板就没有可组装的正文。
     */
    @Test
    void previewRequiresTemplate() {
        BizException ex = assertThrows(BizException.class,
                () -> service.preview("CHAT", "zh-CN", null, null, null, null, "a", null));
        assertTrue(ex.getMessage().contains("先选择"), ex.getMessage());
    }

    /**
     * 已发布版本列表供下拉：只出已发布，草稿不出现。
     */
    @Test
    void publishedOptionsExcludesDrafts() {
        templates.markDraft(CHAT_TEMPLATE_ID);
        List<AgentPromptTemplateOption> options = service.publishedOptions("CHAT", "zh-CN");
        assertTrue(options.stream().noneMatch(item -> item.getId() == CHAT_TEMPLATE_ID));
    }

    /**
     * 段落视图带 display_name，前端 Label 直接可用；core/soft 位语义透传。
     */
    @Test
    void viewExposesDisplayNameAndMutable() {
        AgentPromptTemplateView view = service.view(CHAT_TEMPLATE_ID);
        assertEquals("CHAT", view.getPurpose());
        assertEquals(1, view.getVersion());
        assertEquals(2, view.getSegments().size());
        assertTrue(view.getSegments().stream()
                .anyMatch(item -> "core_contract".equals(item.getSegKey())
                        && "输出契约".equals(item.getDisplayName()) && item.getMutable() == 0));
        assertTrue(view.getSegments().stream()
                .anyMatch(item -> "role_tone".equals(item.getSegKey())
                        && "角色与语气".equals(item.getDisplayName()) && item.getMutable() == 1));
    }

    /**
     * 读路径容错：脏 JSON 不抛异常，按无覆盖回显，避免详情页整页打不开。
     */
    @Test
    void readOverrideSafeToleratesBrokenJson() {
        assertTrue(service.readOverrideSafe("{不是 JSON").isEmpty());
        assertEquals(Map.of("role_tone", "ok"), service.readOverrideSafe("{\"role_tone\":\"ok\"}"));
    }

    /**
     * 用途维度：只有 PromptPurpose 覆盖的用途能挂模板；VISION 等返回 false。
     */
    @Test
    void supportsPurposeMatchesPromptPurposes() {
        assertTrue(service.supportsPurpose("CHAT"));
        assertTrue(service.supportsPurpose("ROUTER"));
        assertFalse(service.supportsPurpose("VISION"));
        assertFalse(service.supportsPurpose(null));
    }

    /** 内存版主表仓储：只提供本测试需要的两个用途各一个版本。 */
    private static final class FakeTemplates implements PromptTemplateRepository {

        private final Map<Long, PromptTemplate> rows = new LinkedHashMap<>();

        private FakeTemplates() {
            rows.put(CHAT_TEMPLATE_ID, row(CHAT_TEMPLATE_ID, "CHAT", "对话主模板"));
            rows.put(ROUTER_TEMPLATE_ID, row(ROUTER_TEMPLATE_ID, "ROUTER", "路由判定模板"));
        }

        private static PromptTemplate row(long id, String purpose, String name) {
            PromptTemplate row = new PromptTemplate();
            row.setId(id);
            row.setTemplateKey(purpose);
            row.setName(name);
            row.setPurpose(purpose);
            row.setLocale("zh-CN");
            row.setVersion(1);
            row.setStatus(1);
            row.setIsActive(1);
            return row;
        }

        private void markDraft(long id) {
            rows.get(id).setStatus(0);
        }

        @Override
        public com.agenthub.common.api.PageResult<PromptTemplate> page(
                com.agenthub.prompt.domain.model.PromptTemplateQuery query) {
            return com.agenthub.common.api.PageResult.of(rows.size(), 1, 10, new ArrayList<>(rows.values()));
        }

        @Override
        public List<PromptTemplate> listByLocale(String locale) {
            return new ArrayList<>(rows.values());
        }

        @Override
        public List<PromptTemplate> listPublishedByPurpose(String purpose, String locale) {
            return rows.values().stream()
                    .filter(item -> purpose.equals(item.getPurpose()) && item.getStatus() == 1)
                    .toList();
        }

        @Override
        public Optional<PromptTemplate> findById(Long id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public int maxVersion(String templateKey, String locale) {
            return 1;
        }

        @Override
        public Long insert(PromptTemplate template, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateDraft(PromptTemplate template, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearActive(String templateKey, String locale, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearActiveByPurpose(String purpose, String locale, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markPublishedActive(Long id, Long operatorId) {
            throw new UnsupportedOperationException();
        }
    }

    /** 内存版段落仓储：core_contract 锁定 + role_tone 可覆盖，与种子同形。 */
    private static final class FakeSegments implements PromptSegmentRepository {

        @Override
        public List<PromptSegment> listByTemplateId(Long templateId) {
            List<PromptSegment> list = new ArrayList<>();
            list.add(segment(templateId, "core_contract", "输出契约", 10, 0, 1,
                    "CORE 输出契约：先直接回答，再补充解释。"));
            list.add(segment(templateId, "role_tone", "角色与语气", 20, 1, 0,
                    "SOFT 默认语气：专业、简洁。"));
            return list;
        }

        private static PromptSegment segment(Long templateId, String key, String name, int sortNo,
                                             int mutable, int required, String content) {
            PromptSegment segment = new PromptSegment();
            segment.setTemplateId(templateId);
            segment.setSegKey(key);
            segment.setDisplayName(name);
            segment.setSortNo(sortNo);
            segment.setMutable(mutable);
            segment.setRequired(required);
            segment.setContent(content);
            return segment;
        }

        @Override
        public void replaceAll(Long templateId, List<PromptSegment> segments) {
            throw new UnsupportedOperationException();
        }
    }
}
