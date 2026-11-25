package com.agenthub.rag.application;

import com.agenthub.rag.domain.model.RagKnowledgeBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 检索工具出场判定与工具说明清单。所属域：rag 的 application。
 * <p>
 * 三条口径：
 * <ol>
 *   <li>没有可用库（未绑 / 绑定全关 / 库都不可检索）→ 不交出工具，说明为空；</li>
 *   <li>有可用库 → 说明里逐个列出库名与描述，顺序即绑定序；</li>
 *   <li>描述为空 → 仍出现在清单里，明确标注「尚无描述」，不得悄悄拿掉。</li>
 * </ol>
 * 判据完全复用 {@link AgentKbBindAppService#listUsableForAgent}，本类不另造「已绑定」定义。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KbSearchToolExposureTest {

    @Mock
    private AgentKbBindAppService binds;

    /**
     * 未绑任何库：说明为空，调用方据此整条不交出。
     */
    @Test
    void emptyWhenNoUsableKnowledgeBase() {
        when(binds.listUsableForAgent(7L)).thenReturn(List.of());
        KbSearchToolExposure exposure = new KbSearchToolExposure(binds);

        assertTrue(exposure.descriptionIfExposed(7L).isEmpty(), "无可用库时不应给出工具说明");
    }

    /**
     * Agent 上下文缺失：同样不交出，避免模型对着空清单空跑。
     */
    @Test
    void emptyWhenAgentMissing() {
        KbSearchToolExposure exposure = new KbSearchToolExposure(binds);

        assertTrue(exposure.descriptionIfExposed(null).isEmpty());
    }

    /**
     * 两个有描述的库：说明含两个库名与描述，且顺序与绑定序一致。
     */
    @Test
    void listsNamesAndDescriptionsInBindOrder() {
        when(binds.listUsableForAgent(7L)).thenReturn(List.of(
                kb(1L, "商城规则库", "退换货与运费规则"),
                kb(2L, "企业制度库", "请假、考勤与报销制度")));
        KbSearchToolExposure exposure = new KbSearchToolExposure(binds);

        String description = exposure.descriptionIfExposed(7L).orElseThrow();

        int mall = description.indexOf("《商城规则库》：退换货与运费规则");
        int policy = description.indexOf("《企业制度库》：请假、考勤与报销制度");
        assertTrue(mall > 0, "第一个库的名称与描述必须出现");
        assertTrue(policy > mall, "第二库排在后面");
        // 说明仍保留原有的能力边界：无命中要明说、不可用只需告知稍后再试
        assertTrue(description.contains("禁止编造"));
        assertTrue(description.contains(RagSearchTool.TOOL_DESCRIPTION));
        // 不允许模型点名库：参数约束不在说明里，说明也不暗示可以指定库
        assertTrue(description.contains("调用时无法指定库"));
    }

    /**
     * 空描述的库仍要在清单里，并明确标注尚无描述。
     */
    @Test
    void keepsUndescribedKnowledgeBaseWithExplicitPlaceholder() {
        when(binds.listUsableForAgent(7L)).thenReturn(List.of(
                kb(1L, "有描述库", "售后问答"),
                kb(2L, "无描述库", "   ")));
        KbSearchToolExposure exposure = new KbSearchToolExposure(binds);

        String description = exposure.descriptionIfExposed(7L).orElseThrow();

        assertTrue(description.contains("《无描述库》：" + KbSearchToolExposure.NO_DESCRIPTION),
                "空描述必须留在清单里并标注尚无描述");
        assertTrue(description.contains("《有描述库》：售后问答"));
    }

    /**
     * 超长描述按上限截断并留省略号，避免一个库把工具说明吃满。
     */
    @Test
    void clipsOverlongDescription() {
        String longText = "规".repeat(300);
        when(binds.listUsableForAgent(7L)).thenReturn(List.of(kb(1L, "长描述库", longText)));
        KbSearchToolExposure exposure = new KbSearchToolExposure(binds);

        String description = exposure.descriptionIfExposed(7L).orElseThrow();

        assertFalse(description.contains(longText), "超长描述不得原样进说明");
        assertTrue(description.contains("…"));
    }

    /**
     * 只有名字没有名称的脏数据也不能出现空书名号。
     */
    @Test
    void blankNameFallsBackToPlaceholder() {
        when(binds.listUsableForAgent(7L)).thenReturn(List.of(kb(1L, "  ", "说明")));
        KbSearchToolExposure exposure = new KbSearchToolExposure(binds);

        String description = exposure.descriptionIfExposed(7L).orElseThrow();

        assertTrue(description.contains("《（未命名知识库）》"));
    }

    /**
     * 工具名判定只认现网调用名；空值与其它工具名一律不算。
     */
    @Test
    void recognisesOnlyKnowledgeToolName() {
        assertTrue(KbSearchToolExposure.isKnowledgeTool("searchKnowledgeBase"));
        assertTrue(KbSearchToolExposure.isKnowledgeTool(" searchKnowledgeBase "));
        assertFalse(KbSearchToolExposure.isKnowledgeTool("queryWeather"));
        assertFalse(KbSearchToolExposure.isKnowledgeTool(null));
        assertFalse(KbSearchToolExposure.isKnowledgeTool("  "));
    }

    /**
     * 可用库列表可空实现兜底成空列表，装配期不会因脏实现抛异常。
     */
    @Test
    void nullUsableListIsTreatedAsEmpty() {
        when(binds.listUsableForAgent(7L)).thenReturn(null);
        KbSearchToolExposure exposure = new KbSearchToolExposure(binds);

        assertEquals(Optional.empty(), exposure.descriptionIfExposed(7L));
        assertEquals(List.of(), exposure.usableOf(7L));
    }

    private static RagKnowledgeBase kb(Long id, String name, String description) {
        RagKnowledgeBase kb = new RagKnowledgeBase();
        kb.setId(id);
        kb.setName(name);
        kb.setDescription(description);
        kb.setStatus("READY");
        kb.setVisibility("PUBLIC");
        kb.setIsolationMode("PHYSICAL");
        return kb;
    }
}
