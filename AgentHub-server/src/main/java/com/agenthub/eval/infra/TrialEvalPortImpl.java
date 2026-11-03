package com.agenthub.eval.infra;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.eval.application.EvalRunFlight;
import com.agenthub.eval.domain.model.EvalArtifact;
import com.agenthub.eval.domain.model.EvalTrialRequest;
import com.agenthub.eval.domain.service.TrialEvalPort;
import com.agenthub.obs.domain.model.ObsTrace;
import com.agenthub.obs.domain.model.ObsTraceQuery;
import com.agenthub.obs.domain.repository.ObsTraceRepository;
import com.agenthub.runtime.api.dto.EventResp;
import com.agenthub.runtime.api.dto.MessageResp;
import com.agenthub.runtime.api.dto.SessionResp;
import com.agenthub.runtime.application.TrialRunAppService;
import com.agenthub.runtime.application.TrialSessionAppService;
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.RunStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * TrialEvalPort 实现。所属域：eval 的 infra。
 * <p><b>唯一允许碰到 runtime 的出站口</b>：只调用
 * {@link TrialSessionAppService#create(Long)} / {@link TrialSessionAppService#rename(Long, String)} /
 * {@link TrialSessionAppService#cancel(Long)} 与
 * {@link TrialRunAppService#send(Long, String)} / {@link TrialRunAppService#approvePlan(Long)}。
 * <p>刻意不做：直调 ReAct/Executor、自开 HTTP 打 8080、OpenJudge、Embedding。
 * <p>channel=EVAL：现网会话表无 channel 列，本实现把 {@code eval:{runId}:{caseKey}} 写入
 * session_title，便于观测过滤（定稿 §2.2 的「标题带 eval_run_id + case_key」）。
 * <p>重试：session 级 ≤1；超时用单次 Future.get，到期 cancel 会话，不再重炸。
 * <p><b>鉴权透传（hotfix）</b>：超时 worker 无 Spring {@code RequestContextHolder}，
 * Sa-Token 读不到登录态 →「未登录或登录已过期」。开跑前 {@link EvalSaRequestContext#capture()}，
 * worker 内 {@link EvalSaRequestContext#runWith} 恢复；不改 trial 门面签名、不第二套 HTTP 客户端。
 */
@Slf4j
@Component
public class TrialEvalPortImpl implements TrialEvalPort {

    /** 终答截断上限，避免 artifact_summary_json 膨胀 */
    private static final int FINAL_ANSWER_MAX = 4000;

    private final TrialSessionAppService sessions;
    private final TrialRunAppService runs;
    private final ObsTraceRepository traces;
    private final EvalRunFlight flight;

    /**
     * @param sessions 试跑会话门面
     * @param runs     试跑发送门面
     * @param traces   只读拉 traceId（可空结果不判 ERROR）
     * @param flight   in-flight 登记，供 cancel 级联
     */
    public TrialEvalPortImpl(TrialSessionAppService sessions, TrialRunAppService runs,
                             ObsTraceRepository traces, EvalRunFlight flight) {
        this.sessions = sessions;
        this.runs = runs;
        this.traces = traces;
        this.flight = flight;
    }

    /**
     * {@inheritDoc}
     * <p>超时：在独立线程跑 send；到期取消会话并抛 BizException，由 Runner 收成 ERROR。
     * <p>身份：在请求线程捕获 {@link RequestAttributes}，worker 内恢复后再调 trial 门面。
     */
    @Override
    public EvalArtifact runCase(EvalTrialRequest request) {
        if (request == null || request.getAgentId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评测试跑请求缺少 agentId");
        }
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评测用例 message 不能为空");
        }
        // 必须在任何 worker 提交之前捕获：RequestContextHolder 不可继承
        RequestAttributes webAttrs = EvalSaRequestContext.capture();
        EvalSaRequestContext.requirePresent(webAttrs);

        long timeoutMs = Math.max(1_000L, request.getTimeoutMs());
        long wallStart = System.currentTimeMillis();

        // create / rename 仍在请求线程：与试跑页同鉴权前提
        SessionResp session = sessions.create(request.getAgentId());
        String sessionId = String.valueOf(session.getId());
        // 尽早登记，cancel 才能命中「还在 send 里」的会话
        if (request.getEvalRunId() != null) {
            flight.bind(request.getEvalRunId(), sessionId);
        }
        // 标题带 eval 标记；失败不阻断（rename 冲突极少，吞掉只打日志）
        try {
            sessions.rename(session.getId(), buildEvalTitle(request));
        } catch (RuntimeException ex) {
            log.warn("评测会话标题写入失败 session={}：{}", sessionId, ex.getMessage());
        }

        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "eval-trial-" + sessionId);
            t.setDaemon(true);
            return t;
        });
        // send / approve 在 worker：恢复 RequestContextHolder，否则 Sa-Token 报未登录
        Future<MessageResp> future = pool.submit(() ->
                EvalSaRequestContext.runWith(webAttrs, () -> executeTurn(session.getId(), request)));
        try {
            MessageResp resp = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return toArtifact(sessionId, resp, System.currentTimeMillis() - wallStart);
        } catch (TimeoutException ex) {
            future.cancel(true);
            safeCancel(sessionId);
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "评测 case 超时（" + timeoutMs + "ms）：" + request.getCaseKey());
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof BizException biz) {
                throw biz;
            }
            throw new BizException(ErrorCode.INTERNAL, "评测试跑失败：" + brief(cause));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            safeCancel(sessionId);
            throw new BizException(ErrorCode.BAD_REQUEST, "评测试跑被中断：" + request.getCaseKey());
        } finally {
            pool.shutdownNow();
            if (request.getEvalRunId() != null) {
                flight.clear(request.getEvalRunId());
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void cancelSession(String sessionId) {
        safeCancel(sessionId);
    }

    /**
     * 发消息；若停在待批准计划，按 plan_action 自动批/拒（EVAL 通道默认 auto_approve）。
     */
    private MessageResp executeTurn(Long sessionId, EvalTrialRequest request) {
        MessageResp resp = runs.send(sessionId, request.getMessage());
        String action = request.getPlanAction() == null || request.getPlanAction().isBlank()
                ? "auto_approve" : request.getPlanAction().trim().toLowerCase(Locale.ROOT);
        // P&E：评测不得弹人工窗；skip 则保留 PENDING 状态交给 Runner 记 ERROR
        int guard = 0;
        while (resp != null && resp.isPlanPending() && guard++ < 3) {
            if ("auto_reject".equals(action)) {
                resp = runs.rejectPlan(sessionId);
                break;
            }
            if ("skip".equals(action)) {
                break;
            }
            resp = runs.approvePlan(sessionId);
        }
        return resp;
    }

    private EvalArtifact toArtifact(String sessionId, MessageResp resp, long latencyMs) {
        EvalArtifact artifact = new EvalArtifact();
        artifact.setSessionId(sessionId);
        artifact.setLatencyMs((int) Math.min(latencyMs, Integer.MAX_VALUE));
        artifact.setCacheHit(Boolean.FALSE);
        if (resp == null) {
            artifact.setStatus(RunStatus.FAILED.name());
            artifact.setErrorSummary("试跑无返回");
            return artifact;
        }
        artifact.setStatus(resp.getStatus());
        artifact.setFinalAnswer(clip(resp.getAssistantPreview(), FINAL_ANSWER_MAX));
        artifact.setErrorSummary(resp.getErrorSummary());
        if (resp.isPlanPending()) {
            artifact.setFinishReason("plan_pending");
            if (artifact.getErrorSummary() == null || artifact.getErrorSummary().isBlank()) {
                artifact.setErrorSummary("计划待批准未自动处理");
            }
        }
        artifact.setToolCalls(summarizeTools(resp.getEvents()));
        artifact.setUsage(sumUsage(resp.getEvents()));
        artifact.setTraceId(lookupTraceId(sessionId));
        return artifact;
    }

    private List<EvalArtifact.ToolCallSummary> summarizeTools(List<EventResp> events) {
        List<EvalArtifact.ToolCallSummary> list = new ArrayList<>();
        if (events == null) {
            return list;
        }
        for (EventResp event : events) {
            if (event == null || event.getEventType() == null) {
                continue;
            }
            if (!EventType.TOOL.name().equalsIgnoreCase(event.getEventType())
                    && !"tool".equalsIgnoreCase(event.getEventType())) {
                continue;
            }
            EvalArtifact.ToolCallSummary one = new EvalArtifact.ToolCallSummary();
            // summary 通常是工具名或短描述；没有则退回 eventType
            one.setName(event.getSummary() == null || event.getSummary().isBlank()
                    ? "tool" : clip(event.getSummary(), 64));
            one.setSuccess(Boolean.TRUE);
            list.add(one);
        }
        return list;
    }

    private EvalArtifact.UsageSummary sumUsage(List<EventResp> events) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        int prompt = 0;
        int completion = 0;
        boolean any = false;
        for (EventResp event : events) {
            if (event.getTokenPrompt() != null) {
                prompt += event.getTokenPrompt();
                any = true;
            }
            if (event.getTokenCompletion() != null) {
                completion += event.getTokenCompletion();
                any = true;
            }
        }
        if (!any) {
            return null;
        }
        EvalArtifact.UsageSummary usage = new EvalArtifact.UsageSummary();
        usage.setPrompt(prompt);
        usage.setCompletion(completion);
        usage.setTotal(prompt + completion);
        return usage;
    }

    /**
     * 只读查该会话最近一条 trace；obs 不可用或无行时返回 null（不定 ERROR）。
     */
    private String lookupTraceId(String sessionId) {
        try {
            long id = Long.parseLong(sessionId);
            ObsTraceQuery query = new ObsTraceQuery();
            query.setSessionId(id);
            query.setCurrent(1);
            query.setSize(1);
            List<ObsTrace> rows = traces.page(query).getRecords();
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            return rows.get(0).getTraceId();
        } catch (RuntimeException ex) {
            log.warn("评测拉取 traceId 失败 session={}：{}", sessionId, ex.getMessage());
            return null;
        }
    }

    private void safeCancel(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            sessions.cancel(Long.parseLong(sessionId));
        } catch (BizException ex) {
            // 已终态 / 正在执行等冲突：收尾路径允许吞掉，避免二次炸
            log.info("评测取消会话跳过 session={}：{}", sessionId, ex.getMessage());
        } catch (RuntimeException ex) {
            log.warn("评测取消会话失败 session={}：{}", sessionId, ex.getMessage());
        }
    }

    private static String buildEvalTitle(EvalTrialRequest request) {
        String caseKey = request.getCaseKey() == null ? "-" : request.getCaseKey();
        String raw = "eval:" + request.getEvalRunId() + ":" + caseKey;
        return clip(raw, 64);
    }

    private static String clip(String raw, int max) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }

    private static String brief(Throwable ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        int cut = message.indexOf('\n');
        return cut > 0 ? message.substring(0, cut) : message;
    }
}
