package com.agenthub.runtime.infra.agentscope;

import com.agenthub.billing.domain.BillingMeter;
import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.SpanClose;
import com.agenthub.obs.domain.model.SpanType;
import com.agenthub.runtime.domain.model.MeterScope;
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
            safeRecord(scope, MeterRemarks.from(ex));
            safeLlmFailure(scope, MeterRemarks.from(ex));
            throw ex;
        }
    }

    /**
     * 客户端还没建成就没有 stream。补一条已结束的 LLM 步骤，避免这一步在链路上消失。
     */
    private void safeLlmFailure(MeterScope scope, String remark) {
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
