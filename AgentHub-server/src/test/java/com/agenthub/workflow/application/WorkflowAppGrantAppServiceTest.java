package com.agenthub.workflow.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.workflow.api.dto.WorkflowAppGrantItemResp;
import com.agenthub.workflow.domain.enums.WorkflowDefStatus;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowRelease;
import com.agenthub.workflow.support.InMemoryWorkflowStore;
import com.agenthub.workflow.support.WorkflowGrantTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 应用-工作流授权用例单测。所属域：workflow 的 application。
 * <p>覆盖：只允许授权已发布定义、空列表撤销、列表回显名称与默认发布指针。
 */
class WorkflowAppGrantAppServiceTest {

    private static final long TENANT_ID = 1L;
    private static final long APP_ID = 100L;

    private InMemoryWorkflowStore store;
    private WorkflowGrantTestSupport grantStore;
    private WorkflowAppGrantAppService service;
    private WorkflowRelease release;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowStore();
        grantStore = new WorkflowGrantTestSupport();
        release = grantStore.putRelease(10L, 1, "{\"nodes\":[],\"edges\":[]}");
        service = new WorkflowAppGrantAppService(grantStore.grants(), store.defs(), grantStore.releases(),
                () -> TENANT_ID, operator(), (m, a, t, id, s) -> {
        });
    }

    @Test
    @DisplayName("已发布定义：保存成功并回显名称")
    void savePublishedDef() {
        store.putDef(def(10L, WorkflowDefStatus.published(), release.getId()));

        service.save(APP_ID, List.of(10L));

        List<WorkflowAppGrantItemResp> items = service.list(APP_ID);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getWorkflowId()).isEqualTo(10L);
        assertThat(items.get(0).getName()).isEqualTo("订单流程");
        assertThat(items.get(0).getDefaultReleaseId()).isEqualTo(release.getId());
    }

    @Test
    @DisplayName("从未发布的定义：拒授权，中文提示先发布")
    void rejectDraftDef() {
        store.putDef(def(10L, WorkflowDefStatus.initial(), null));

        assertThatThrownBy(() -> service.save(APP_ID, List.of(10L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("尚未发布");
        assertThat(service.list(APP_ID)).isEmpty();
    }

    @Test
    @DisplayName("跨租户定义：按不存在处理")
    void rejectOtherTenantDef() {
        WorkflowDef other = def(10L, WorkflowDefStatus.published(), release.getId());
        other.setTenantId(2L);
        store.putDef(other);

        assertThatThrownBy(() -> service.save(APP_ID, List.of(10L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("工作流不存在");
    }

    @Test
    @DisplayName("空列表：撤销全部授权")
    void emptyListRevokes() {
        store.putDef(def(10L, WorkflowDefStatus.published(), release.getId()));
        service.save(APP_ID, List.of(10L));

        service.save(APP_ID, List.of());

        assertThat(service.list(APP_ID)).isEmpty();
    }

    @Test
    @DisplayName("可授权清单只出已发布定义，并带默认版本号")
    void publishedDefsOnlyReturnsReleased() {
        store.putDef(def(10L, WorkflowDefStatus.published(), release.getId()));
        store.putDef(def(11L, WorkflowDefStatus.initial(), null));

        List<WorkflowAppGrantItemResp> items = service.publishedDefs();

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getWorkflowId()).isEqualTo(10L);
        assertThat(items.get(0).getDefaultVersion()).isEqualTo(1);
    }

    private WorkflowDef def(Long id, String status, Long defaultReleaseId) {
        WorkflowDef def = new WorkflowDef();
        def.setId(id);
        def.setTenantId(TENANT_ID);
        def.setCode("order_flow");
        def.setName("订单流程");
        def.setStatus(status);
        def.setDefaultReleaseId(defaultReleaseId);
        return def;
    }

    private OperatorContext operator() {
        return new OperatorContext() {
            @Override
            public Long userIdOrNull() {
                return 1L;
            }

            @Override
            public long requireUserId() {
                return 1L;
            }

            @Override
            public String usernameOrNull() {
                return "admin";
            }

            @Override
            public String clientIp() {
                return "127.0.0.1";
            }

            @Override
            public String userAgent() {
                return "test";
            }

            @Override
            public String requestMethod() {
                return "POST";
            }

            @Override
            public String requestUri() {
                return "/test";
            }
        };
    }
}
