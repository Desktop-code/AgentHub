package com.agenthub.workflow.domain.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 处理人规则单测。所属域：workflow 的 domain。
 *
 * <p>覆盖 W4 验收的解析与命中口径：ROLE 命中/未命中、USER 命中/未命中、空规则兼容、
 * 非法规则给出中文原因。纯函数，不碰 Spring / Sa-Token / 数据库。
 */
class WorkflowAssigneeRuleTest {

    @Test
    @DisplayName("空规则：任何人（有 human:act 即可）都命中")
    void emptyRuleMatchesAnyone() {
        assertThat(WorkflowAssigneeRule.parse(null).kind()).isEqualTo(WorkflowAssigneeRule.Kind.EMPTY);
        assertThat(WorkflowAssigneeRule.parse("   ").kind()).isEqualTo(WorkflowAssigneeRule.Kind.EMPTY);
        assertThat(WorkflowAssigneeRule.matches(null, 7L, List.of())).isTrue();
        assertThat(WorkflowAssigneeRule.matches("  ", 7L, List.of("ops"))).isTrue();
        assertThat(WorkflowAssigneeRule.describe(null)).contains("任何人");
    }

    @Test
    @DisplayName("USER：命中名单任一 id 通过，未命中拒绝")
    void userRuleMatchesListedIds() {
        assertThat(WorkflowAssigneeRule.matches("USER:1, 7 ,9", 7L, List.of())).isTrue();
        assertThat(WorkflowAssigneeRule.matches("USER:1,9", 7L, List.of())).isFalse();
        assertThat(WorkflowAssigneeRule.parse("user:1,2").kind())
                .isEqualTo(WorkflowAssigneeRule.Kind.USER);
        assertThat(WorkflowAssigneeRule.parse("USER:1,2").userIds()).containsExactly(1L, 2L);
        // 去重：同一 id 重复写不放大扫描成本
        assertThat(WorkflowAssigneeRule.parse("USER:3,3").userIds()).containsExactly(3L);
    }

    @Test
    @DisplayName("ROLE：角色码命中（忽略大小写）通过，未命中拒绝")
    void roleRuleMatchesRoleCode() {
        assertThat(WorkflowAssigneeRule.matches("ROLE:ops_admin", 7L, List.of("ops_admin"))).isTrue();
        assertThat(WorkflowAssigneeRule.matches("ROLE:ops_admin", 7L, List.of("OPS_ADMIN"))).isTrue();
        assertThat(WorkflowAssigneeRule.matches("ROLE:ops_admin", 7L, List.of("viewer"))).isFalse();
        assertThat(WorkflowAssigneeRule.matches("ROLE:ops_admin", 7L, List.of())).isFalse();
        assertThat(WorkflowAssigneeRule.parse("ROLE:ops_admin").roleCode()).isEqualTo("ops_admin");
    }

    @Test
    @DisplayName("非法规则：给出中文原因且不命中（不允许静默降级成空规则）")
    void invalidRulesReportProblem() {
        assertThat(WorkflowAssigneeRule.parse("nobody").problem()).contains("只能写");
        assertThat(WorkflowAssigneeRule.parse("USER:").problem()).contains("至少");
        assertThat(WorkflowAssigneeRule.parse("USER:abc").problem()).contains("不是数字");
        assertThat(WorkflowAssigneeRule.parse("USER:0").problem()).contains("正整数");
        assertThat(WorkflowAssigneeRule.parse("ROLE:").problem()).contains("必须写角色码");
        assertThat(WorkflowAssigneeRule.parse("ROLE:bad code").problem()).contains("不合法");
        assertThat(WorkflowAssigneeRule.matches("nobody", 7L, List.of("ops_admin"))).isFalse();
        assertThat(WorkflowAssigneeRule.describe("nobody")).contains("规则非法");
    }

    @Test
    @DisplayName("规则长度上限与展示文案")
    void describeAndLength() {
        String tooLong = "USER:" + "1".repeat(WorkflowAssigneeRule.MAX_RULE_LENGTH);
        assertThat(WorkflowAssigneeRule.parse(tooLong).problem()).contains("超过上限");
        assertThat(WorkflowAssigneeRule.describe("USER:1,2")).isEqualTo("指定用户：1,2");
        assertThat(WorkflowAssigneeRule.describe("ROLE:ops")).isEqualTo("角色：ops");
    }
}
