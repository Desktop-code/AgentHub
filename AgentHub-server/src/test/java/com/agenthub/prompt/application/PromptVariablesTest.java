package com.agenthub.prompt.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.prompt.PromptTestFixtures;
import com.agenthub.prompt.domain.model.PromptContext;
import com.agenthub.prompt.domain.service.PromptVariables;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 变量替换与防二次注入的门禁。所属域：prompt。
 * <p>
 * 架构定稿 §5.2 要求「发布 / 单测门禁必须包含恶意占位符用例」；
 * 本类只测变量引擎本身：递归、未注册、未赋值、值含占位符四类边界。
 * 组装层的同一断言在 {@code PromptAssemblerTest} 里也有一份，两层都锁。
 */
class PromptVariablesTest {

    /**
     * 模板侧占位符被替换一次；值侧的占位符原样落地。
     */
    @Test
    void substitutesTemplateSideOnceAndKeepsValueLiteral() {
        String template = "时间={{current_time}} 用户={{user_query}}";
        String hostile = "{{current_time}} 与 ${current_time}";

        String text = PromptVariables.render(template, java.util.Map.of(
                "current_time", "2026-10-31 04:00:00",
                "user_query", hostile));

        assertTrue(text.contains("时间=2026-10-31 04:00:00"));
        // 值侧两种形态都必须原样保留
        assertTrue(text.endsWith("用户=" + hostile));
        assertFalse(text.contains("用户=2026-10-31 04:00:00"));
    }

    /**
     * 非递归：值的形态像占位符时不得被再解释成另一个变量。
     */
    @Test
    void valueLookingLikePlaceholderIsNotResolved() {
        String text = PromptVariables.render("A={{agent_name}} B={{user_query}}", java.util.Map.of(
                "agent_name", "助手甲",
                "user_query", "{{agent_name}}"));

        assertEquals("A=助手甲 B={{agent_name}}", text);
    }

    /**
     * 未注册变量：阻断。模板里写白名单外的名字属于配置错误，不能送模型。
     */
    @Test
    void unregisteredVariableFails() {
        BizException ex = assertThrows(BizException.class,
                () -> PromptVariables.render("正文 {{api_key}}", java.util.Map.of()));
        assertEquals(ErrorCode.PROMPT_UNAVAILABLE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("未注册变量"));
    }

    /**
     * 已注册但未赋值：严格模式失败，不把 {{var}} 原文送出去。
     */
    @Test
    void assignedMissingFailsStrictly() {
        BizException ex = assertThrows(BizException.class,
                () -> PromptVariables.render("正文 {{user_query}}", java.util.Map.of()));
        assertEquals(ErrorCode.PROMPT_UNAVAILABLE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("未赋值"));
    }

    /**
     * 只有开括号没有闭括号：按字面量保留，不当作坏模板炸掉整段提示词。
     */
    @Test
    void loneOpenBraceStaysLiteral() {
        assertEquals("前缀 {{ 未闭合", PromptVariables.render("前缀 {{ 未闭合", java.util.Map.of()));
    }

    /**
     * 占位符内文本非法（含表达式形态）：阻断，避免模板被当成小语言执行。
     */
    @Test
    void illegalPlaceholderNameFails() {
        BizException ex = assertThrows(BizException.class,
                () -> PromptVariables.render("正文 {{user.query}}", java.util.Map.of()));
        assertEquals(ErrorCode.PROMPT_UNAVAILABLE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("非法占位符"));
    }

    /**
     * occurrences 只收集合法名字，供保存期校验「模板用了白名单外的变量」。
     */
    @Test
    void occurrencesCollectsOnlyValidNames() {
        var found = PromptVariables.occurrences("{{user_query}} {{user_query}} {{bad-name}} {{agent_name}}");
        assertTrue(found.containsKey("user_query"));
        assertEquals(2, found.get("user_query"));
        assertTrue(found.containsKey("agent_name"));
        assertFalse(found.containsKey("bad-name"));
    }

    /**
     * 平台默认语言回落：Context 未指定 locale 时按 zh-CN 取模板与 L1。
     */
    @Test
    void blankLocaleFallsBackToDefault() {
        PromptTestFixtures.InMemorySnapshots snapshots = PromptTestFixtures.snapshots();
        PromptAssembler assembler = PromptTestFixtures.assembler(snapshots);

        var result = assembler.assemble(PromptContext.builder().purpose("CHAT").locale("   ").build());

        assertEquals("zh-CN", result.getLocale());
        assertEquals(1, result.getTemplateVersion());
    }

    /**
     * L1 换版后，组装结果必须跟随生效版（发布已清缓存）。
     */
    @Test
    void newConstraintVersionIsPickedUp() {
        PromptTestFixtures.InMemorySnapshots snapshots = PromptTestFixtures.snapshots();
        snapshots.publishConstraint("【平台约束·第2版】新红线。", 2);
        PromptAssembler assembler = PromptTestFixtures.assembler(snapshots);

        var result = assembler.assemble(PromptContext.builder().purpose("CHAT").build());

        assertEquals(2, result.getConstraintVersion());
        assertTrue(result.getText().startsWith("【平台约束·第2版】"));
    }

    /**
     * L1 关闭（排障开关）：不阻断、不拼 L1，其余段落照常。
     */
    @Test
    void constraintDisabledSkipsL1WithoutBlocking() {
        PromptTestFixtures.InMemorySnapshots snapshots = PromptTestFixtures.snapshots();
        snapshots.withoutConstraint();
        PromptAssembler assembler = new PromptAssembler(
                snapshots, new com.fasterxml.jackson.databind.ObjectMapper(), false, "zh-CN", 24_000);

        var result = assembler.assemble(PromptContext.builder().purpose("CHAT").build());

        assertEquals(0, result.getConstraintVersion());
        assertTrue(result.getText().startsWith("CORE 输出契约"));
        // 关闭 L1 时不得残留任何平台约束文案
        assertFalse(result.getText().contains("【平台约束"));
    }
}
