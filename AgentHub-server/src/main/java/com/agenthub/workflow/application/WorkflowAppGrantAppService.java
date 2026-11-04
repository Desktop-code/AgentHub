package com.agenthub.workflow.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.workflow.api.dto.WorkflowAppGrantItemResp;
import com.agenthub.workflow.domain.enums.WorkflowDefStatus;
import com.agenthub.workflow.domain.model.AppWorkflowGrant;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowDefQuery;
import com.agenthub.workflow.domain.model.WorkflowRelease;
import com.agenthub.workflow.domain.repository.AppWorkflowGrantRepository;
import com.agenthub.workflow.domain.repository.WorkflowDefRepository;
import com.agenthub.workflow.domain.repository.WorkflowReleaseRepository;
import com.agenthub.workflow.domain.service.WorkflowTenantProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 应用-工作流授权用例。所属域：workflow 的 application。
 * <p>边界：只维护「哪个 app 能触发哪个 def」；不创建 app（admin 域），
 * 也不改 release（发布只走 {@code WorkflowDefAppService}）。
 * <p>为什么保存是「整体对齐」：前端是一次多选提交，逐条增删更容易出现半套授权；
 * 对齐后也天然支持撤销。
 * <p>草稿防线：只有 {@code default_release_id} 非空的 def 才能被授权，
 * 避免出现「应用能触发一个从未发布的草稿」。
 */
@Service
public class WorkflowAppGrantAppService {

    private final AppWorkflowGrantRepository grants;
    private final WorkflowDefRepository defs;
    private final WorkflowReleaseRepository releases;
    private final WorkflowTenantProvider tenantProvider;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;

    /**
     * @param grants         授权仓储
     * @param defs           定义仓储（校验存在 / 租户 / 已发布）
     * @param releases       发布版本仓储（回显默认版本号）
     * @param tenantProvider 当前租户
     * @param operatorContext 控制台操作人
     * @param operLogWriter   操作日志
     */
    public WorkflowAppGrantAppService(AppWorkflowGrantRepository grants, WorkflowDefRepository defs,
                                      WorkflowReleaseRepository releases,
                                      WorkflowTenantProvider tenantProvider, OperatorContext operatorContext,
                                      OperLogWriter operLogWriter) {
        this.grants = grants;
        this.defs = defs;
        this.releases = releases;
        this.tenantProvider = tenantProvider;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
    }

    /**
     * 某个应用当前的工作流授权。
     *
     * @param appId 应用 id
     * @return 授权行（含 def 名称 / 编码）；定义被删时仍返回 grant，供前端提示清理
     */
    public List<WorkflowAppGrantItemResp> list(Long appId) {
        long tenantId = tenantProvider.currentTenantId();
        List<WorkflowAppGrantItemResp> items = new ArrayList<>();
        for (AppWorkflowGrant grant : grants.listByAppId(appId)) {
            WorkflowAppGrantItemResp item = new WorkflowAppGrantItemResp();
            item.setWorkflowId(grant.getWorkflowDefId());
            item.setReleaseId(grant.getReleaseId());
            item.setStatus(grant.getStatus());
            item.setCreateTime(grant.getCreateTime());
            WorkflowDef def = defs.findById(grant.getWorkflowDefId()).orElse(null);
            // 定义被物理删或跨租户：仍回行，但名称留空，由前端标「已失效」
            if (def != null && def.getTenantId() != null && def.getTenantId() == tenantId) {
                fillDef(item, def);
            }
            items.add(item);
        }
        return items;
    }

    /**
     * 可授权的工作流：当前租户下已发布（HAS_RELEASE）的定义。
     * <p>为什么不复用定义分页接口：应用管理员未必持有 {@code workflow:def:view}，
     * 这里用应用侧权限码即可拿到「能授权什么」，不扩大控制台权限。
     *
     * @return 最多 100 条；未发布定义不出现
     */
    public List<WorkflowAppGrantItemResp> publishedDefs() {
        WorkflowDefQuery query = new WorkflowDefQuery();
        query.setCurrent(1);
        query.setSize(100);
        query.setStatus(WorkflowDefStatus.published());
        query.setTenantId(tenantProvider.currentTenantId());
        List<WorkflowAppGrantItemResp> items = new ArrayList<>();
        for (WorkflowDef def : defs.page(query).getRecords()) {
            WorkflowAppGrantItemResp item = new WorkflowAppGrantItemResp();
            item.setWorkflowId(def.getId());
            fillDef(item, def);
            items.add(item);
        }
        return items;
    }

    /**
     * 把定义的可展示字段填进授权行：编码 / 名称 / 默认发布指针与版本号。
     *
     * @param item 目标行
     * @param def  定义
     */
    private void fillDef(WorkflowAppGrantItemResp item, WorkflowDef def) {
        item.setCode(def.getCode());
        item.setName(def.getName());
        item.setDefaultReleaseId(def.getDefaultReleaseId());
        if (def.getDefaultReleaseId() != null) {
            // 版本号只用于展示；查不到不影响授权本身
            WorkflowRelease release = releases.findById(def.getDefaultReleaseId()).orElse(null);
            item.setDefaultVersion(release == null ? null : release.getVersion());
        }
    }

    /**
     * 用给定 def 集合对齐授权。
     *
     * @param appId        应用 id
     * @param workflowIds  目标工作流定义 id；空表示清空
     * @throws BizException 定义不存在 / 跨租户 / 从未发布
     */
    public void save(Long appId, List<Long> workflowIds) {
        long tenantId = tenantProvider.currentTenantId();
        // 去重并保序，避免前端重复勾选导致唯一键冲突
        Set<Long> targets = new LinkedHashSet<>();
        if (workflowIds != null) {
            for (Long id : workflowIds) {
                if (id != null && id > 0) {
                    targets.add(id);
                }
            }
        }
        for (Long workflowId : targets) {
            WorkflowDef def = defs.findById(workflowId)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "工作流不存在：" + workflowId));
            if (def.getTenantId() == null || def.getTenantId() != tenantId) {
                throw new BizException(ErrorCode.NOT_FOUND, "工作流不存在：" + workflowId);
            }
            // 草稿防线：应用只能触发已发布的定义（默认发布指针必须存在）
            if (def.getDefaultReleaseId() == null
                    || !WorkflowDefStatus.published().equals(def.getStatus())) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "工作流「" + def.getName() + "」尚未发布，不能授权应用触发；请先在编排页发布版本");
            }
        }
        grants.replace(appId, targets, operatorContext.requireUserId());
        operLogWriter.write("workflow", "app_grant", "app", String.valueOf(appId),
                "应用工作流授权数量 " + targets.size());
    }

    /**
     * 入站触发前的授权校验。
     *
     * @param appId        应用 id
     * @param workflowDefId 工作流定义 id
     * @return 有效授权（含可能的 release 钉死）
     * @throws BizException FORBIDDEN 未授权或授权已停用
     */
    public AppWorkflowGrant requireEnabled(Long appId, Long workflowDefId) {
        return grants.findEnabled(appId, workflowDefId)
                .orElseThrow(() -> new BizException(ErrorCode.FORBIDDEN,
                        "该应用未获此工作流的触发授权，请在「应用管理 → 工作流授权」中绑定"));
    }
}
