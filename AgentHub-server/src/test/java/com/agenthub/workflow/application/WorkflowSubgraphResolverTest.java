package com.agenthub.workflow.application;

import com.agenthub.workflow.domain.WorkflowNodeFailure;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowRelease;
import com.agenthub.workflow.domain.repository.WorkflowDefRepository;
import com.agenthub.workflow.domain.repository.WorkflowReleaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 子图发布版本解析的运行时校验。所属域：workflow 的 application。
 *
 * <p>钉住三条拒绝口径（全部面向用户、不回落草稿 / 最新版）：
 * 发布版本缺失；版本不属于所绑工作流；工作流不属于当前租户。
 */
class WorkflowSubgraphResolverTest {

    private static final long TENANT = 1L;
    private static final long WORKFLOW = 10L;
    private static final long RELEASE = 100L;

    private WorkflowReleaseRepository releases;
    private WorkflowDefRepository defs;
    private WorkflowSubgraphResolver resolver;

    @BeforeEach
    void setUp() {
        releases = mock(WorkflowReleaseRepository.class);
        defs = mock(WorkflowDefRepository.class);
        resolver = new WorkflowSubgraphResolver(releases, defs,
                new WorkflowGraphCodec(new ObjectMapper()));
    }

    @Test
    @DisplayName("发布版本缺失：中文失败，不静默改跑草稿 / 最新默认版")
    void missingReleaseFails() {
        when(releases.findById(RELEASE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(TENANT, WORKFLOW, RELEASE))
                .isInstanceOf(WorkflowNodeFailure.class)
                .hasMessageContaining("发布版本已不存在")
                .hasMessageNotContaining("Exception");
    }

    @Test
    @DisplayName("版本不属于所绑工作流：中文失败，不拿别的图顶替")
    void mismatchedReleaseFails() {
        WorkflowRelease release = new WorkflowRelease();
        release.setId(RELEASE);
        release.setWorkflowId(WORKFLOW + 1);
        when(releases.findById(RELEASE)).thenReturn(Optional.of(release));

        assertThatThrownBy(() -> resolver.resolve(TENANT, WORKFLOW, RELEASE))
                .isInstanceOf(WorkflowNodeFailure.class)
                .hasMessageContaining("不属于所选工作流");
    }

    @Test
    @DisplayName("跨租户：即使知道别的租户 releaseId 也拒绝，不跑过去")
    void crossTenantFails() {
        WorkflowRelease release = new WorkflowRelease();
        release.setId(RELEASE);
        release.setWorkflowId(WORKFLOW);
        WorkflowDef def = new WorkflowDef();
        def.setId(WORKFLOW);
        def.setTenantId(TENANT + 1);
        when(releases.findById(RELEASE)).thenReturn(Optional.of(release));
        when(defs.findById(WORKFLOW)).thenReturn(Optional.of(def));

        assertThatThrownBy(() -> resolver.resolve(TENANT, WORKFLOW, RELEASE))
                .isInstanceOf(WorkflowNodeFailure.class)
                .hasMessageContaining("不属于当前租户");
    }

    @Test
    @DisplayName("未绑定工作流 / 未选择版本：都按中文业务失败收口")
    void unboundFails() {
        assertThatThrownBy(() -> resolver.resolve(TENANT, null, RELEASE))
                .isInstanceOf(WorkflowNodeFailure.class)
                .hasMessageContaining("未绑定被调工作流");
        assertThatThrownBy(() -> resolver.resolve(TENANT, WORKFLOW, null))
                .isInstanceOf(WorkflowNodeFailure.class)
                .hasMessageContaining("未选择发布版本");
        assertThat(resolver.tryResolve(TENANT, WORKFLOW, RELEASE)).isEmpty();
    }
}
