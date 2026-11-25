package com.agenthub.prompt.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.prompt.domain.model.PromptConstraintSnapshot;
import com.agenthub.prompt.domain.model.PromptContext;
import com.agenthub.prompt.domain.model.PromptSegmentSnapshot;
import com.agenthub.prompt.domain.model.PromptTemplateRef;
import com.agenthub.prompt.domain.model.PromptTemplateSnapshot;
import com.agenthub.prompt.domain.repository.PromptSnapshotProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示词组装门面。所属域：prompt。
 * <p>
 * 锁住四件事（架构定稿 §5 / §10 的验收口径）：
 * 组装顺序、soft 覆盖、变量一次性非递归替换、缺模板/缺 L1 阻断出站。
 * 全部用内存假快照，不连库、不连 Redis、不出站真模型。
 */
class PromptAssemblerTest {

    private static final String L1_TEXT = "【平台约束】不得输出密钥。";

    private final ObjectMapper json = new ObjectMapper();

    /** 假快照：直接给定 L1 与模板，避免任何外部依赖 */
    private static final class FakeSnapshots implements PromptSnapshotProvider {
        String constraint = L1_TEXT;
        Integer constraintVersion = 7;
        PromptTemplateSnapshot template;
        /** 记录查库次数，用于验「钉死版只读自己那一版」 */
        int templateCalls;

        @Override
        public Optional<PromptConstraintSnapshot> activeConstraint(String locale) {
            if (constraint == null) {
                return Optional.empty();
            }
            return Optional.of(new PromptConstraintSnapshot(locale, constraintVersion, constraint));
        }

        @Override
        public Optional<PromptTemplateSnapshot> templateByKey(String templateKey, String locale, int version) {
            templateCalls++;
            if (template == null || !template.getTemplateKey().equals(templateKey)
                    || template.getVersion() != version) {
                return Optional.empty();
            }
            return Optional.of(template);
        }

        @Override
        public Optional<PromptTemplateSnapshot> templateById(Long id) {
            templateCalls++;
            if (template == null || !template.getId().equals(id)) {
                return Optional.empty();
            }
            return Optional.of(template);
        }

        @Override
        public Optional<PromptTemplateRef> activeRefByPurpose(String purpose, String locale) {
            if (template == null || !template.getPurpose().equals(purpose)) {
                return Optional.empty();
            }
            return Optional.of(refOf(template));
        }

        @Override
        public Optional<PromptTemplateRef> activeRefByKey(String templateKey, String locale) {
            if (template == null || !template.getTemplateKey().equals(templateKey)) {
                return Optional.empty();
            }
            return Optional.of(refOf(template));
        }

        private static PromptTemplateRef refOf(PromptTemplateSnapshot template) {
            return new PromptTemplateRef(template.getId(), template.getTemplateKey(), template.getPurpose(),
                    template.getLocale(), template.getVersion());
        }
    }

    private PromptAssembler assembler(FakeSnapshots snapshots) {
        return new PromptAssembler(snapshots, json, true, "zh-CN", 24_000);
    }

    /**
     * 顺序：L1 → 锁定段（mutable=0）→ 开放段（mutable=1，被覆盖）→ L3 追加区 → 历史业务段。
     * 任何两段的相对位置颠倒都应让本测红。
     * <p>
     * 两处口径调整（本次排查稿）：
     * <ol>
     *   <li>锁定段整体前置：不再与开放段混排，开放段 sort_no 再小也不会抢到契约之前；</li>
     *   <li>历史业务段紧贴 L3 收口（放 L3 之后）：它与「补充要求」语义重复，
     *       夹在 L2 与 L3 之间会割裂「开放段 + L3 覆写」的连续性，暂不能并入 L3 时明确标注过渡。</li>
     * </ol>
     */
    @Test
    void assemblesInFixedOrderWithCoreBeforeSoft() {
        FakeSnapshots snapshots = new FakeSnapshots();
        snapshots.template = chatTemplate();
        PromptContext context = PromptContext.builder()
                .purpose("CHAT")
                .overrideJson("{\"role_tone\":\"SOFT_OVERRIDE 覆盖后的语气\"}")
                .businessSegment("BUSINESS 历史业务段")
                .promptExtra("EXTRA 追加要求")
                .build();

        String text = assembler(snapshots).assemble(context).getText();

        assertTrue(text.startsWith(L1_TEXT), "L1 必须最前");
        int core = text.indexOf("CORE 输出契约");
        int soft = text.indexOf("SOFT_OVERRIDE 覆盖后的语气");
        int extra = text.indexOf("EXTRA 追加要求");
        int business = text.indexOf("BUSINESS 历史业务段");
        assertTrue(core > 0, "core 段必须存在");
        assertTrue(soft > core, "soft 覆盖段在 core 之后");
        assertTrue(extra > soft, "L3 追加区在 L2 开放段之后");
        assertTrue(business > extra, "历史业务段紧贴 L3 收口（过渡期放在 L3 之后）");
        // 被覆盖的 soft 段原文不得残留，否则等于两段同时注入
        assertFalse(text.contains("SOFT_DEFAULT 默认语气"));
    }

    /**
     * 相位优先：开放段的 sort_no 即使比锁定段更小，也必须排在锁定段之后。
     * 这是代码保证，不依赖库里数字碰巧填对。
     */
    @Test
    void lockedPhaseWinsEvenWhenSoftHasSmallerSortNo() {
        FakeSnapshots snapshots = new FakeSnapshots();
        PromptTemplateSnapshot template = chatTemplate();
        // 开放段 sort_no=5、锁定段 sort_no=99：按旧实现开放段会跑到锁定段前面
        template.getSegments().get(0).setSortNo(99);
        template.getSegments().get(1).setSortNo(5);
        snapshots.template = template;

        String text = assembler(snapshots).assemble(PromptContext.builder().purpose("CHAT").build()).getText();

        assertTrue(text.indexOf("CORE 输出契约") < text.indexOf("SOFT_DEFAULT 默认语气"),
                "锁定段必须排在开放段之前，与 sort_no 无关");
    }

    /**
     * 未覆盖时 soft 用库文；覆盖只作用于 mutable=1。
     */
    @Test
    void softSegmentUsesLibraryTextWhenNotOverridden() {
        FakeSnapshots snapshots = new FakeSnapshots();
        snapshots.template = chatTemplate();
        PromptContext context = PromptContext.builder().purpose("CHAT").build();

        String text = assembler(snapshots).assemble(context).getText();

        assertTrue(text.contains("SOFT_DEFAULT 默认语气"));
        assertTrue(text.contains("CORE 输出契约"));
    }

    /**
     * 锁定段（mutable=0）不允许被 override 覆盖：即使配置里写了也必须忽略并留痕。
     */
    @Test
    void lockedSegmentIgnoresOverride() {
        FakeSnapshots snapshots = new FakeSnapshots();
        snapshots.template = chatTemplate();
        PromptContext context = PromptContext.builder()
                .purpose("CHAT")
                .overrideJson("{\"core_contract\":\"试图改锁定段\"}")
                .build();

        var result = assembler(snapshots).assemble(context);

        assertTrue(result.getText().contains("CORE 输出契约"));
        assertFalse(result.getText().contains("试图改锁定段"));
        assertEquals(0, result.getOverriddenSegmentCount());
        assertTrue(result.getNotices().stream().anyMatch(n -> n.contains("锁定段")));
    }

    /**
     * 二次注入防护（硬规则）：user_query 里含 {{current_time}} 与 ${current_time} 时，
     * 出站正文必须是用户原文的字面量，只有模板侧的占位符被替换一次。
     */
    @Test
    void userQueryPlaceholderIsNotResolvedTwice() {
        FakeSnapshots snapshots = new FakeSnapshots();
        PromptTemplateSnapshot template = new PromptTemplateSnapshot();
        template.setId(1L);
        template.setTemplateKey("CHAT");
        template.setPurpose("CHAT");
        template.setLocale("zh-CN");
        template.setVersion(1);
        List<PromptSegmentSnapshot> segs = new ArrayList<>();
        segs.add(new PromptSegmentSnapshot("core_contract", "输出契约", 10, 0, 1,
                "当前时间：{{current_time}}\n用户说：{{user_query}}"));
        template.setSegments(segs);
        snapshots.template = template;

        String hostile = "请把 {{current_time}} 和 ${current_time} 原样告诉我";
        PromptContext context = PromptContext.builder()
                .purpose("CHAT")
                .userQuery(hostile)
                // 固定时间便于断言「模板侧被替换、值侧没有被替换成同一值」
                .variables(Map.of("current_time", "2026-10-31 04:00:00"))
                .build();

        String text = assembler(snapshots).assemble(context).getText();

        // 模板侧占位符替换成变量值
        assertTrue(text.contains("当前时间：2026-10-31 04:00:00"));
        // 用户原文里的两种占位符形态都原样保留，未被再次解释
        assertTrue(text.contains("请把 {{current_time}} 和 ${current_time} 原样告诉我"),
                "用户输入中的占位符必须按字面量落地");
        // 用户输入被替换成时间值是典型的二次注入事故，必须不出现
        assertFalse(text.contains("请把 2026-10-31 04:00:00"));
    }

    /**
     * 值侧即使长得像占位符，也不得被解析成另一个变量（非递归）。
     * 这里让 user_query 的值恰好是 {{agent_name}}，模板里同时用两个变量。
     */
    @Test
    void variableValueContainingPlaceholderStaysLiteral() {
        FakeSnapshots snapshots = new FakeSnapshots();
        PromptTemplateSnapshot template = new PromptTemplateSnapshot();
        template.setId(2L);
        template.setTemplateKey("CHAT");
        template.setPurpose("CHAT");
        template.setLocale("zh-CN");
        template.setVersion(1);
        template.setSegments(List.of(new PromptSegmentSnapshot("core_contract", "契约", 10, 0, 1,
                "助手={{agent_name}} 用户={{user_query}}")));
        snapshots.template = template;

        PromptContext context = PromptContext.builder()
                .purpose("CHAT")
                .agentName("真实助手名")
                .userQuery("{{agent_name}}")
                .build();

        String text = assembler(snapshots).assemble(context).getText();

        assertTrue(text.contains("助手=真实助手名"));
        // 用户原文按字面量落地，不会变成「用户=真实助手名」
        assertTrue(text.contains("用户={{agent_name}}"));
    }

    /**
     * 无生效 L1：阻断出站，不允许拼半截。
     */
    @Test
    void missingConstraintBlocksOutbound() {
        FakeSnapshots snapshots = new FakeSnapshots();
        snapshots.constraint = null;
        snapshots.template = chatTemplate();

        BizException ex = assertThrows(BizException.class,
                () -> assembler(snapshots).assemble(PromptContext.builder().purpose("CHAT").build()));

        assertEquals(ErrorCode.PROMPT_UNAVAILABLE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("平台约束"));
    }

    /**
     * 用途未配置模板：阻断，且禁止拿 CHAT 模板顶替 ROUTER。
     */
    @Test
    void missingTemplateForPurposeBlocksAndNeverFallsBackToChat() {
        FakeSnapshots snapshots = new FakeSnapshots();
        // 库里只有 CHAT 模板，但请求的是 ROUTER
        snapshots.template = chatTemplate();

        BizException ex = assertThrows(BizException.class,
                () -> assembler(snapshots).assemble(PromptContext.builder().purpose("ROUTER").build()));

        assertEquals(ErrorCode.PROMPT_UNAVAILABLE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("用途未配置模板"));
    }

    /**
     * 模板 purpose 与请求用途不一致：即使被显式钉死，也阻断。
     */
    @Test
    void mismatchedPurposeOnPinnedTemplateBlocks() {
        FakeSnapshots snapshots = new FakeSnapshots();
        snapshots.template = chatTemplate();

        BizException ex = assertThrows(BizException.class, () -> assembler(snapshots).assemble(
                PromptContext.builder().purpose("ROUTER").templateId(1L).build()));

        assertEquals(ErrorCode.PROMPT_UNAVAILABLE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("用途不匹配"));
    }

    /**
     * 缺必选段（required=1 但正文为空）：阻断出站。
     */
    @Test
    void missingRequiredSegmentBlocks() {
        FakeSnapshots snapshots = new FakeSnapshots();
        PromptTemplateSnapshot template = chatTemplate();
        template.getSegments().get(0).setContent("   ");
        snapshots.template = template;

        BizException ex = assertThrows(BizException.class,
                () -> assembler(snapshots).assemble(PromptContext.builder().purpose("CHAT").build()));

        assertEquals(ErrorCode.PROMPT_UNAVAILABLE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("必选段"));
    }

    /**
     * 钉死版本的请求只认自己那一版：平台发新版（库里 version 变成 2）时，
     * 钉死 v1 的请求若拿不到 v1 就必须失败，而不是悄悄用 v2。
     */
    @Test
    void pinnedVersionNeverSilentlyUpgrades() {
        FakeSnapshots snapshots = new FakeSnapshots();
        PromptTemplateSnapshot v2 = chatTemplate();
        v2.setVersion(2);
        v2.getSegments().get(0).setContent("CORE v2 新契约");
        snapshots.template = v2;

        BizException ex = assertThrows(BizException.class, () -> assembler(snapshots).assemble(
                PromptContext.builder().purpose("CHAT").templateKey("CHAT").templateVersion(1).build()));

        assertEquals(ErrorCode.PROMPT_UNAVAILABLE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("v1"));
    }

    /**
     * 模板里写了未注册变量：阻断，不能把 {{xxx}} 原文送给模型。
     */
    @Test
    void unregisteredVariableInTemplateBlocks() {
        FakeSnapshots snapshots = new FakeSnapshots();
        PromptTemplateSnapshot template = chatTemplate();
        template.getSegments().get(0).setContent("CORE 契约 {{secret_token}}");
        snapshots.template = template;

        BizException ex = assertThrows(BizException.class,
                () -> assembler(snapshots).assemble(PromptContext.builder().purpose("CHAT").build()));

        assertEquals(ErrorCode.PROMPT_UNAVAILABLE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("未注册变量"));
    }

    /**
     * 预算裁剪只发生在可截断 tail，且会给出可见标记；L1 与锁定段必须完整保留。
     * <p>
     * 这里刻意让锁定段自己就超过预算：正确行为是「tail 全部省略 + 留痕」，
     * 而不是为了压到预算内去截断 L1/锁定段——红线宁可超预算，也不能被截成半句仍出站。
     */
    @Test
    void overBudgetTruncatesTailAndKeepsL1AndLockedSegment() {
        FakeSnapshots snapshots = new FakeSnapshots();
        PromptTemplateSnapshot template = chatTemplate();
        String longCore = "CORE " + "长".repeat(400);
        template.getSegments().get(0).setContent(longCore);
        snapshots.template = template;
        PromptAssembler small = new PromptAssembler(snapshots, json, true, "zh-CN", 200);

        var result = small.assemble(PromptContext.builder().purpose("CHAT").build());

        assertTrue(result.isTruncated());
        assertTrue(result.getText().startsWith(L1_TEXT), "L1 不得被裁");
        assertTrue(result.getText().contains(longCore), "锁定段不得被裁，即使它自己就超预算");
        assertFalse(result.getText().contains("SOFT_DEFAULT 默认语气"), "开放段先让出");
        assertTrue(result.getText().endsWith("（提示词已按预算截断）"));
        // 结构描述必须说明 head 有多长，下游才能据此保护首部
        assertTrue(result.getLayout().structured());
        assertTrue(result.getLayout().headChars() > 200);
    }

    /**
     * tail 超预算但 head 还能装下：从尾部截断，head 与部分开放段保留。
     */
    @Test
    void overBudgetKeepsHeadAndTrimsTail() {
        FakeSnapshots snapshots = new FakeSnapshots();
        PromptTemplateSnapshot template = chatTemplate();
        template.getSegments().get(1).setContent("SOFT " + "长".repeat(400));
        snapshots.template = template;
        PromptAssembler small = new PromptAssembler(snapshots, json, true, "zh-CN", 260);

        var result = small.assemble(PromptContext.builder().purpose("CHAT").build());

        assertTrue(result.isTruncated());
        assertTrue(result.getText().startsWith(L1_TEXT));
        assertTrue(result.getText().contains("CORE 输出契约"));
        assertTrue(result.getText().endsWith("（提示词已按预算截断）"));
        assertTrue(result.getText().length() <= 260 + 20);
    }

    /**
     * 组装结果只暴露版本与长度，正文不写日志。这里用 marker 检查日志里没有正文。
     */
    @Test
    void assemblerDoesNotLogPromptBody() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PromptAssembler.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        FakeSnapshots snapshots = new FakeSnapshots();
        snapshots.template = chatTemplate();
        try {
            assembler(snapshots).assemble(PromptContext.builder()
                    .purpose("CHAT")
                    .businessSegment("UNIQUE_BUSINESS_MARKER_QR7")
                    .build());
            assertFalse(appender.list.isEmpty());
            for (ch.qos.logback.classic.spi.ILoggingEvent event : appender.list) {
                assertFalse(event.getFormattedMessage().contains("UNIQUE_BUSINESS_MARKER_QR7"));
                assertFalse(event.getFormattedMessage().contains("不得输出密钥"));
            }
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * 记忆抽取只拼运行时锁定段：不注 L1、不拼 soft 段，也不拼只锁编辑的遗留 memory_style；
     * 追加要求只放在最后。
     */
    @Test
    void lockedOnlyAssemblyIgnoresL1AndSoftSegments() {
        FakeSnapshots snapshots = new FakeSnapshots();
        snapshots.template = memoryTemplate();

        String text = assembler(snapshots).assembleLockedByPurpose("MEMORY", "zh-CN", "EXTRA 追加要求");

        assertFalse(text.contains(L1_TEXT), "记忆抽取不注 L1 平台约束");
        assertTrue(text.contains("CORE 抽取契约"));
        assertTrue(text.contains("CORE 输出格式"));
        assertFalse(text.contains("SOFT 场景追加"), "开放段不参与记忆抽取组装");
        assertFalse(text.contains("key/value/confidence"), "遗留 memory_style 旧格式不得回调进运行时");
        assertTrue(text.indexOf("CORE 抽取契约") < text.indexOf("CORE 输出格式"),
                "锁定段按 sort_no 顺序拼接");
        assertTrue(text.endsWith("【补充要求】\nEXTRA 追加要求"), "追加要求只追加在锁定段之后");
    }

    /**
     * 缺少平台要求的 output_format 锁定段（旧版 MEMORY 模板）必须阻断，不能拿半截提示词出站。
     */
    @Test
    void lockedOnlyAssemblyRejectsTemplateWithoutOutputFormat() {
        FakeSnapshots snapshots = new FakeSnapshots();
        PromptTemplateSnapshot template = memoryTemplate();
        template.getSegments().removeIf(segment -> "output_format".equals(segment.getSegKey()));
        snapshots.template = template;

        BizException ex = assertThrows(BizException.class,
                () -> assembler(snapshots).assembleLockedByPurpose("MEMORY", "zh-CN", null));

        assertEquals(ErrorCode.PROMPT_UNAVAILABLE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("output_format"), ex.getMessage());
    }

    /**
     * MEMORY 模板样例：抽取契约 + 输出格式为运行时锁定段；
     * memory_style 是 v1 遗留段（旧 key/value/confidence），只锁编辑、不参与出站；另有一条 soft 段。
     */
    private static PromptTemplateSnapshot memoryTemplate() {
        PromptTemplateSnapshot template = new PromptTemplateSnapshot();
        template.setId(5L);
        template.setTemplateKey("MEMORY");
        template.setPurpose("MEMORY");
        template.setLocale("zh-CN");
        template.setVersion(2);
        List<PromptSegmentSnapshot> segs = new ArrayList<>();
        segs.add(new PromptSegmentSnapshot("core_contract", "抽取契约", 10, 0, 1, "CORE 抽取契约"));
        segs.add(new PromptSegmentSnapshot("output_format", "输出格式", 20, 0, 1, "CORE 输出格式"));
        segs.add(new PromptSegmentSnapshot("memory_style", "旧输出格式", 30, 0, 0,
                "LEGACY key/value/confidence 旧格式"));
        segs.add(new PromptSegmentSnapshot("scene_style", "场景追加", 40, 1, 0, "SOFT 场景追加"));
        template.setSegments(segs);
        return template;
    }

    /**
     * CHAT 模板样例：core 锁定 + soft 开放，覆盖真实种子结构。
     */
    private static PromptTemplateSnapshot chatTemplate() {
        PromptTemplateSnapshot template = new PromptTemplateSnapshot();
        template.setId(1L);
        template.setTemplateKey("CHAT");
        template.setPurpose("CHAT");
        template.setLocale("zh-CN");
        template.setVersion(1);
        List<PromptSegmentSnapshot> segs = new ArrayList<>();
        segs.add(new PromptSegmentSnapshot("core_contract", "输出契约", 10, 0, 1, "CORE 输出契约"));
        segs.add(new PromptSegmentSnapshot("role_tone", "角色与语气", 20, 1, 0, "SOFT_DEFAULT 默认语气"));
        template.setSegments(segs);
        return template;
    }
}
