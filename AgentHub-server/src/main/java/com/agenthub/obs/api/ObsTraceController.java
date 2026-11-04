package com.agenthub.obs.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.api.Result;
import com.agenthub.obs.api.dto.ObsSessionPageReq;
import com.agenthub.obs.api.dto.ObsSessionResp;
import com.agenthub.obs.api.dto.ObsSpanResp;
import com.agenthub.obs.api.dto.ObsTracePageReq;
import com.agenthub.obs.api.dto.ObsTraceResp;
import com.agenthub.obs.application.TraceQueryAppService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 调用追踪查询。所属域：obs。一期只读，没有删除接口。
 */
@RestController
@RequestMapping("/api/obs")
public class ObsTraceController {

    private final TraceQueryAppService queries;

    /**
     * @param queries 三栏查询
     */
    public ObsTraceController(TraceQueryAppService queries) {
        this.queries = queries;
    }

    /**
     * 左栏会话。默认全部 Agent，可按 agentId 收窄。
     *
     * @param req 分页、q、可选 agentId
     * @return 时间降序的会话
     */
    @SaCheckPermission(ObsPermissions.TRACE_LIST)
    @GetMapping("/sessions")
    public Result<PageResult<ObsSessionResp>> sessions(@Valid ObsSessionPageReq req) {
        return Result.ok(queries.sessions(req));
    }

    /**
     * 中栏。该会话下的用户提问。
     *
     * @param sessionId 会话主键
     * @param req       分页和 title 关键字
     * @return 时间降序的 trace
     */
    @SaCheckPermission(ObsPermissions.TRACE_LIST)
    @GetMapping("/sessions/{sessionId}/traces")
    public Result<PageResult<ObsTraceResp>> traces(@PathVariable Long sessionId, @Valid ObsTracePageReq req) {
        return Result.ok(queries.traces(sessionId, req));
    }

    /**
     * 按工作流筛选调用记录（工作流列表「运行记录」入口）。
     *
     * @param req 分页、q、可选 workflowId；缺省时返回全部工作流运行记录
     * @return 匹配的 trace，时间降序
     */
    @SaCheckPermission(ObsPermissions.TRACE_LIST)
    @GetMapping("/traces")
    public Result<PageResult<ObsTraceResp>> tracesByWorkflow(@Valid ObsTracePageReq req) {
        return Result.ok(queries.tracesByWorkflow(req));
    }

    /**
     * @param traceId 业务 traceId
     * @return 一条提问
     */
    @SaCheckPermission(ObsPermissions.TRACE_LIST)
    @GetMapping("/traces/{traceId}")
    public Result<ObsTraceResp> detail(@PathVariable String traceId) {
        return Result.ok(queries.detail(traceId));
    }

    /**
     * 右栏。按 seq 升序的完整步骤树。
     *
     * @param traceId 业务 traceId
     * @return 步骤列表，含 id / parentId / stepNo 和 depth
     */
    @SaCheckPermission(ObsPermissions.SPAN_LIST)
    @GetMapping("/traces/{traceId}/spans")
    public Result<List<ObsSpanResp>> spans(@PathVariable String traceId) {
        return Result.ok(queries.spans(traceId));
    }
}
