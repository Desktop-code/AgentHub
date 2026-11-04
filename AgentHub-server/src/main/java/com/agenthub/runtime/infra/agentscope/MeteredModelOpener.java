package com.agenthub.runtime.infra.agentscope;

import com.agenthub.billing.domain.BillingMeter;
import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.SpanClose;
import com.agenthub.obs.domain.model.SpanType;
import com.agenthub.obs.domain.service.ObsAttrs;
import com.agenthub.runtime.domain.model.MeterScope;
import com.agenthub.runtime.domain.service.CauseDigest;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 打开模型并套上计量。所属域：runtime 的 infra。
 * 客户端还没建成就失败时记一条 0 token，避免这一步完全没有流水。
 */
@Component
public class MeteredModelOpener {

    private static final Logger log = LoggerFactory.getLogger(MeteredModelOpener.class);

    private final AgentScopeModelFactory models;
    private final BillingMeter meter;

    /**
     * @param models 解密并构造模型
     * @param meter  流水写入
     */
    public MeteredModelOpener(AgentScopeModelFactory models, BillingMeter meter) {
        this.models = models;
        this.meter = meter;
    }

    /**
     * @param scope 本轮 Agent、模型和会话
     * @return 已套计量的模型
     */
    public Model open(MeterScope scope) {
        try {
            return new MeteringModel(models.open(scope.modelInstanceId()), meter, scope);
        } catch (RuntimeException ex) {
            // 模型客户端没建成：这一步在本轮里不会有 stream，必须自己补日志与步骤，
            // 否则「连通测 success、试跑失败」在控制台与追踪里都是空白
            log.warn("打开模型失败 instanceId={} agentId={} type={} cause={}",
                    scope.modelInstanceId(), scope.agentId(), ex.getClass().getSimpleName(),
                    CauseDigest.of(ex), ex);
            safeRecord(scope, MeterRemarks.from(ex));
            safeLlmFailure(scope, MeterRemarks.from(ex), ex);
            throw ex;
        }
    }

    /**
     * 客户端还没建成就没有 stream。补一条已结束的 LLM 步骤，避免这一步在链路上消失。
     *
     * @param remark 脱敏后的失败说明
     * @param error  原始异常，可空；非空时脱敏后写进 attrs.cause 供排障
     */
    private void safeLlmFailure(MeterScope scope, String remark, Throwable error) {
        ObsTraceScope trace = ObsTraceHolder.current();
        if (trace == null) {
            return;
        }
        Long spanId = trace.open(SpanType.LLM, "llm", scope.agentId(), trace.implicitParent(), null);
        SpanClose close = new SpanClose();
        close.setStatus(ObsStatus.ERROR);
        close.setErrorSummary(remark);
        close.setModelInstanceId(scope.modelInstanceId());
        close.setTokenPrompt(0);
        close.setTokenCompletion(0);
        close.setTokenTotal(0);
        close.setFinishReason("error");
        close.setAttrsJson(ObsAttrs.withCause(null, CauseDigest.of(error)));
        trace.close(spanId, close);
    }

    private void safeRecord(MeterScope scope, String remark) {
        try {
            meter.record(scope.command(0, 0, remark));
        } catch (RuntimeException ex) {
            log.warn("计量写入失败 type={}", ex.getClass().getSimpleName());
        }
    }
}
