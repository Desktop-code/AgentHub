package com.agenthub.workflow.api;

import com.agenthub.admin.domain.model.AppIdentity;
import com.agenthub.admin.infra.web.AppKeyAuthInterceptor;
import com.agenthub.common.api.Result;
import com.agenthub.workflow.api.dto.WorkflowAppInvokeReq;
import com.agenthub.workflow.api.dto.WorkflowAppInvokeResp;
import com.agenthub.workflow.application.WorkflowAppInvokeAppService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应用入站触发工作流（OpenAPI）。所属域：workflow 的 api。
 * <p>鉴权：不走 Sa-Token，也不吃用户权限码；{@code /api/open/**} 由
 * {@code AppKeyAuthInterceptor} 校验 {@code X-App-Key} + {@code X-App-Secret}，
 * 再把 {@link AppIdentity} 放进 request attribute。
 * <p>语义：只触发已发布的 release 快照，run 记 {@code trigger_type=APP}；立即返回 runId，
 * 引擎异步执行（与试跑同一套引擎 / 硬顶 / 人工卡点）。
 */
@RestController
@RequestMapping("/api/open/workflow")
public class WorkflowOpenController {

    private final WorkflowAppInvokeAppService service;

    /**
     * @param service 应用触发用例
     */
    public WorkflowOpenController(WorkflowAppInvokeAppService service) {
        this.service = service;
    }

    /**
     * 创建一次应用触发的 run。
     *
     * <p>请求头：{@code X-App-Key}、{@code X-App-Secret}
     * <p>失败口径：401 密钥错误 / 应用不存在；403 应用停用或未授权该工作流；400 未发布或入参非法。
     *
     * @param app 由拦截器写入的应用身份
     * @param req workflowId + input（+ 可选 clientRequestId）
     * @return runId / 初始状态 / 实际绑定的 releaseId
     */
    @PostMapping("/runs")
    public Result<WorkflowAppInvokeResp> invoke(
            @RequestAttribute(AppKeyAuthInterceptor.ATTR_IDENTITY) AppIdentity app,
            @Valid @RequestBody WorkflowAppInvokeReq req) {
        return Result.ok(service.invoke(app, req));
    }
}
