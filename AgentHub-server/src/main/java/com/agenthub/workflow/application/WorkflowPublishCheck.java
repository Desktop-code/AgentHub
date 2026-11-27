package com.agenthub.workflow.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.workflow.domain.model.WorkflowBindingSnapshot;
import com.agenthub.workflow.domain.model.WorkflowGraph;
import com.agenthub.workflow.domain.model.WorkflowIssue;
import com.agenthub.workflow.domain.model.WorkflowValidationResult;
import com.agenthub.workflow.domain.service.WorkflowGraphValidator;
import com.agenthub.workflow.domain.service.WorkflowRunLimits;
import com.agenthub.workflow.infra.WorkflowProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 发布口径的图检查。所属域：workflow 的 application。
 * <p>校验接口、发布和「按当前草稿调试」共用 {@link WorkflowGraphValidator} 的发布严格模式，
 * 再附上运行时限问题。不另起一套规则，也不在这里写库。
 */
@Component
public class WorkflowPublishCheck {

    private final WorkflowBindingResolver bindingResolver;
    private final WorkflowProperties properties;
    private final WorkflowSubgraphGuard subgraphGuard;

    /**
     * 单测 / 旧入口用的构造：不带子图链路检查。
     *
     * @param bindingResolver 图里的 Agent / 工具绑定快照
     * @param properties      节点数与循环次数硬顶
     */
    public WorkflowPublishCheck(WorkflowBindingResolver bindingResolver, WorkflowProperties properties) {
        this(bindingResolver, properties, null);
    }

    /**
     * @param bindingResolver 图里的 Agent / 工具绑定快照
     * @param properties      节点数与循环次数硬顶
     * @param subgraphGuard   子图链路（版本 / 环 / 层级）静态检查
     */
    @Autowired
    public WorkflowPublishCheck(WorkflowBindingResolver bindingResolver, WorkflowProperties properties,
                                WorkflowSubgraphGuard subgraphGuard) {
        this.bindingResolver = bindingResolver;
        this.properties = properties;
        this.subgraphGuard = subgraphGuard;
    }

    /**
     * 按发布前同一套规则检查图（不含子图链路：调用方没给定义身份）。
     *
     * @param graph 已做完结构解析的图
     * @return errors / warns；errors 为空才算通过
     */
    public WorkflowValidationResult check(WorkflowGraph graph) {
        return check(null, 0L, graph);
    }

    /**
     * 按发布前同一套规则检查图，并检查子图链路。
     *
     * @param workflowId 当前工作流定义主键；可空（空则不检查子图链路）
     * @param tenantId   当前工作流所属租户
     * @param graph      已做完结构解析的图
     * @return errors / warns；errors 为空才算通过
     */
    public WorkflowValidationResult check(Long workflowId, long tenantId, WorkflowGraph graph) {
        // 单测的 2 参构造没有绑定解析器：按「空绑定」做结构校验，不因此 NPE
        WorkflowBindingSnapshot bindings = bindingResolver == null
                ? WorkflowBindingSnapshot.empty() : bindingResolver.resolve(graph);
        WorkflowValidationResult result = WorkflowGraphValidator.validate(graph, bindings, true,
                properties.effectiveMaxNodesPerGraph(), properties.effectiveMaxLoopIterations());
        for (String message : WorkflowRunLimits.issues(graph, properties.effectiveWallMs())) {
            result.getErrors().add(new WorkflowIssue("RUN_LIMITS_INVALID", message, null, null));
        }
        // 子图：版本必须仍在、属于所绑工作流、同租户、无环、不超过 3 层
        if (workflowId != null && subgraphGuard != null) {
            result.getErrors().addAll(subgraphGuard.check(tenantId, graph));
        }
        return result;
    }

    /**
     * 不通过就拒绝开跑。调用方此时还没有插入 run。
     *
     * @param graph 已做完结构解析的图
     * @throws BizException 有 error 时，文案带最多 3 条摘要
     */
    public void requirePublishable(WorkflowGraph graph) {
        requirePublishable(null, 0L, graph);
    }

    /**
     * 不通过就拒绝开跑；带定义身份时一并检查子图链路。
     *
     * @param workflowId 当前工作流定义主键；可空
     * @param tenantId   当前工作流所属租户
     * @param graph      已做完结构解析的图
     * @throws BizException 有 error 时，文案带最多 3 条摘要
     */
    public void requirePublishable(Long workflowId, long tenantId, WorkflowGraph graph) {
        WorkflowValidationResult result = check(workflowId, tenantId, graph);
        if (!result.passed()) {
            throw new BizException(ErrorCode.BAD_REQUEST, failureMessage(result));
        }
    }

    /**
     * 保存草稿前只拦子图链路的环 / 超深 / 版本不可用；其它业务错误仍允许先存草稿。
     *
     * @param workflowId 当前工作流定义主键
     * @param tenantId   当前工作流所属租户
     * @param graph      已解析的图
     * @throws BizException 子图链路不可保存时
     */
    public void requireSubgraphSafe(Long workflowId, long tenantId, WorkflowGraph graph) {
        if (workflowId == null || subgraphGuard == null) {
            return;
        }
        List<WorkflowIssue> issues = subgraphGuard.check(tenantId, graph);
        if (issues.isEmpty()) {
            return;
        }
        String head = issues.stream().limit(3)
                .map(issue -> issue.getMessage() == null ? issue.getCode() : issue.getMessage())
                .reduce((left, right) -> left + "；" + right)
                .orElse("");
        String suffix = issues.size() > 3 ? " 等 " + issues.size() + " 项" : "";
        throw new BizException(ErrorCode.BAD_REQUEST, "草稿保存失败：" + head + suffix);
    }

    /**
     * 调试被校验拦住时的中文摘要。完整列表仍由校验接口返回。
     */
    private String failureMessage(WorkflowValidationResult result) {
        List<String> messages = result.getErrors().stream()
                .map(issue -> issue.getMessage() == null ? issue.getCode() : issue.getMessage())
                .toList();
        String head = messages.stream().limit(3).reduce((left, right) -> left + "；" + right).orElse("");
        String suffix = messages.size() > 3 ? " 等 " + messages.size() + " 项" : "";
        return "调试校验未通过：" + head + suffix;
    }
}
