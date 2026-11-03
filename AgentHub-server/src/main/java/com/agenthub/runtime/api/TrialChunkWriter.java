package com.agenthub.runtime.api;

import com.agenthub.runtime.api.dto.MessageResp;
import com.agenthub.runtime.api.sse.ServletSseFrameSink;
import com.agenthub.runtime.api.sse.UnifiedSseWriter;
import com.agenthub.runtime.application.TrialStreamEnd;
import com.agenthub.runtime.domain.model.ChildPlanPendingView;
import com.agenthub.runtime.domain.model.GeneratedFile;
import com.agenthub.runtime.domain.model.PlanStep;
import com.agenthub.runtime.domain.model.PlanView;
import com.agenthub.runtime.domain.model.StepView;
import com.agenthub.runtime.domain.service.GeneratedFileMarker;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.runtime.domain.service.TrialTexts;
import com.agenthub.runtime.model.acl.UnifiedFinishReason;
import com.agenthub.runtime.model.acl.UnifiedStreamEventType;
import com.agenthub.runtime.model.acl.UnifiedToolCall;
import com.agenthub.runtime.model.acl.UnifiedUsage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把试跑增量写成 SSE。所属域：runtime。
 * 在当前请求线程里边跑边刷出，避免异步线程丢掉登录态。出站前脱敏。
 * 结束帧（done）的兜底统一交给 {@link UnifiedSseWriter}：本类只负责把「业务上已经结束」
 * 这件事通知过去，其余四条异常路径（截断 / 超时 / 上游错误 / 客户端取消）由写入器用 CAS 收口。
 */
public final class TrialChunkWriter implements TrialProgress, TrialStreamEnd {

    private final UnifiedSseWriter writer;

    /**
     * @param response 已设为 text/event-stream 的响应
     * @param json     把 data 收成一行，避免换行截断 SSE
     */
    public TrialChunkWriter(HttpServletResponse response, ObjectMapper json) {
        this.writer = new UnifiedSseWriter(new ServletSseFrameSink(response, true), json);
    }

    /**
     * 绑定会话主键，写进 done 载荷供前端刷新历史。
     *
     * @param sessionId 会话主键
     */
    public void bindSession(Long sessionId) {
        writer.bindSession(sessionId);
    }

    /**
     * 是否已发出结束帧。供调用方在收尾时判断是否还需要兜底。
     *
     * @return true 表示 done 已写
     */
    public boolean isDone() {
        return writer.isDone();
    }

    /**
     * 客户端断开或本轮被中止时的收尾。这是出站层独有的兜底点：
     * 上游 Adapter 取消后已无接收方，只有这里还持有 HTTP 连接，能真实尝试补一帧 done。
     *
     * @return true 表示结束帧确实写出
     */
    public boolean cancel() {
        return writer.finishCancelled();
    }

    /**
     * 收尾兜底：若还没发过结束帧就补一帧。Controller 在 finally 里无条件调用。
     * 原因取 STOP 而不是 ERROR：走到这里通常意味着业务正常跑完但没显式收尾，
     * 真正的失败路径（{@link #fail}）早已写入 ERROR 并占位，不会落到这里。
     *
     * @return true 表示本次补出了结束帧
     */
    public boolean ensureFinished() {
        return writer.finish(UnifiedFinishReason.STOP, null, null);
    }

    /** {@inheritDoc} */
    @Override
    public void phase(String phase) {
        String text = phase == null ? "" : phase;
        if (text.isEmpty()) {
            return;
        }
        // phase 在定稿 §4.1 没有统一类型，保留旧事件名直接下发，不编造 unified 语义
        writer.emitPhase(text);
    }

    /** {@inheritDoc} */
    @Override
    public void route(String summary) {
        String text = TrialTexts.redact(summary);
        if (!text.isEmpty()) {
            writer.emitRoute(text);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void reasoning(String delta) {
        String text = TrialTexts.redact(delta);
        if (!text.isEmpty()) {
            writer.thinkingDelta(text);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void tool(String name, String arguments, String result, boolean success) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String line = TrialTexts.toolDisplay(name, arguments);
        payload.put("line", success ? line : line + " · 失败");
        // 兜底清洗：正常路径上工具层已剔除标记，但 MCP 等外部实现不受我们控制。
        // 这里再扫一遍，保证用户在聊天框里永远看不到 [FILE_GENERATED] 这串机械字符。
        payload.put("result", TrialTexts.redact(GeneratedFileMarker.strip(result)));
        payload.put("success", success);
        // 工具执行结果没有 unified 等价事件（定稿里 tool_call.completed 是「模型要求调用」），
        // 故只走旧事件名，避免语义混淆
        writer.emitLegacy("tool", payload);
    }

    /** {@inheritDoc} */
    @Override
    public void assistant(String delta) {
        String text = TrialTexts.redact(delta);
        if (!text.isEmpty()) {
            writer.messageDelta(text);
        }
    }

    /**
     * 图片文字提取结果。走独立事件名 vision，载荷是整段文本。
     * 与 tool 的区别：它没有调用行与结果的拆分，前端按「素材块」渲染即可。
     */
    @Override
    public void vision(String text) {
        String body = TrialTexts.redact(text);
        if (!body.isEmpty()) {
            writer.emitLegacy("vision", body);
        }
    }

    /**
     * 计划生成或状态变化。走独立事件名 plan，载荷是结构化 JSON（planId + state + steps），
     * 不塞进 assistant 正文，前端才能按步骤列表渲染而不是读一段纯文本。
     */
    @Override
    public void plan(PlanView plan) {
        if (plan == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("planId", plan.planId());
        payload.put("state", plan.state().name());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (PlanStep step : plan.steps()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("seq", step.seq());
            one.put("title", TrialTexts.redact(step.title()));
            one.put("detail", TrialTexts.redact(step.detail()));
            steps.add(one);
        }
        payload.put("steps", steps);
        payload.put("summary", TrialTexts.redact(plan.summary()));
        writer.emitLegacy("plan", payload);
    }

    /**
     * 编排根下的子计划待批准。走独立事件名 {@code ma_child_plan_pending}：
     * 根侧批准按钮必须按 route_key 定位具体子，不能复用单 Agent 的 plan 卡。
     */
    @Override
    public void childPlanPending(ChildPlanPendingView pending) {
        if (pending == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("routeKey", pending.routeKey());
        payload.put("childAgentId", pending.childAgentId());
        payload.put("childAgentName", TrialTexts.redact(pending.childAgentName()));
        payload.put("planId", pending.planId());
        payload.put("state", pending.state());
        payload.put("runState", pending.runState());
        payload.put("summary", TrialTexts.redact(pending.summary()));
        List<Map<String, Object>> steps = new ArrayList<>();
        for (PlanStep step : pending.steps()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("seq", step.seq());
            one.put("title", TrialTexts.redact(step.title()));
            one.put("detail", TrialTexts.redact(step.detail()));
            steps.add(one);
        }
        payload.put("steps", steps);
        writer.emitLegacy("ma_child_plan_pending", payload);
    }

    /**
     * 计划单步进度。载荷与 STEP 事件正文同一套字段（seq / title / state / text）。
     */
    @Override
    public void step(StepView step) {
        if (step == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("seq", step.seq());
        payload.put("title", TrialTexts.redact(step.title()));
        payload.put("state", step.state().name());
        payload.put("text", TrialTexts.redact(step.text()));
        payload.put("line", TrialTexts.redact(step.displayLine()));
        writer.emitLegacy("step", payload);
    }

    /**
     * 文件生成。走独立事件名 {@code file_generated}，载荷含前端直接可用的下载地址。
     *
     * <p>为什么下载地址在后端拼：fileName 可能含中文与子目录，编码规则必须与下载接口
     * 一一对应；散到前端拼迟早出现「卡片能显示、点了 404」。
     *
     * @param file 产物名与体积
     */
    @Override
    public void file(GeneratedFile file) {
        if (file == null || file.fileName() == null || file.fileName().isBlank()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fileName", file.fileName());
        payload.put("size", file.size());
        payload.put("downloadUrl", downloadUrl(file.fileName()));
        writer.emitLegacy("file_generated", payload);
    }

    /**
     * 拼下载地址。
     *
     * <p>会话主键取当前 SSE 已绑定的值：整轮开始时 Controller 就 {@code bindSession} 过，
     * 因此在工具回调里必定可用。文件名按 UTF-8 百分号编码放进查询串，
     * 前端无需再处理中文与 {@code #}、{@code &} 之类的字符。
     *
     * @param fileName 相对工作区根的文件名
     * @return 形如 {@code /api/runtime/workspace/download?sessionId=12&fileName=report.md}
     */
    private String downloadUrl(String fileName) {
        Long id = writer.boundSession();
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8);
        return "/api/runtime/workspace/download?sessionId=" + (id == null ? "" : id) + "&fileName=" + encoded;
    }

    /** {@inheritDoc} */
    @Override
    public void done(MessageResp resp) {
        writer.bindSession(resp == null ? null : resp.getSessionId());
        // 业务已给出结论：按 resp 的状态判定结束原因，而不是无条件当成功
        boolean failed = resp != null && "FAILED".equalsIgnoreCase(resp.getStatus());
        UnifiedFinishReason reason = failed ? UnifiedFinishReason.ERROR : UnifiedFinishReason.STOP;
        String summary = resp == null ? null : resp.getErrorSummary();
        // 停在待批准不是异常结束：额外带一个标记，前端据此渲染批准/取消按钮而不是「失败」
        writer.finish(reason, null, summary, resp != null && resp.isPlanPending());
    }

    /** {@inheritDoc} */
    @Override
    public void fail(String message) {
        writer.finishError(message == null || message.isBlank() ? "试跑失败" : message);
    }
}
