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

    /**
     * @param bindingResolver 图里的 Agent / 工具绑定快照
     * @param properties      节点数与循环次数硬顶
     */
    public WorkflowPublishCheck(WorkflowBindingResolver bindingResolver, WorkflowProperties properties) {
        this.bindingResolver = bindingResolver;
        this.properties = properties;
    }

    /**
     * 按发布前同一套规则检查图。
     *
     * @param graph 已做完结构解析的图
     * @return errors / warns；errors 为空才算通过
     */
    public WorkflowValidationResult check(WorkflowGraph graph) {
        WorkflowBindingSnapshot bindings = bindingResolver.resolve(graph);
        WorkflowValidationResult result = WorkflowGraphValidator.validate(graph, bindings, true,
                properties.effectiveMaxNodesPerGraph(), properties.effectiveMaxLoopIterations());
        for (String message : WorkflowRunLimits.issues(graph, properties.effectiveWallMs())) {
            result.getErrors().add(new WorkflowIssue("RUN_LIMITS_INVALID", message, null, null));
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
        WorkflowValidationResult result = check(graph);
        if (!result.passed()) {
            throw new BizException(ErrorCode.BAD_REQUEST, failureMessage(result));
        }
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
