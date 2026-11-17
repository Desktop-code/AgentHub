package com.agenthub.workflow.infra;

import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.SpanClose;
import com.agenthub.obs.domain.model.SpanType;
import com.agenthub.obs.domain.service.ObsAttrs;

/**
 * 把工作流里的子 Agent 记到当前对话 trace 上。所属域：workflow 的 infra。
 *
 * <p>工作流自己的节点步仍写在 WORKFLOW 通道那条 trace。对话页只绑了控制台 trace，
 * 模型调用会落到外壳下面，子 Agent 本身却看不见。这里在对话 trace 已经绑定时，
 * 于外壳下再开一条真专家 AGENT，让随后的模型步挂到这位专家上。
 * 没有对话 trace（工作流页单独运行）时什么都不做，不新造外壳。
 */
public final class WorkflowConsoleAgentSpan {

    private WorkflowConsoleAgentSpan() {
    }

    /**
     * 在对话 trace 的当前父（工作流外壳）下开一条子 Agent，并把栈父切到它。
     *
     * @param trace   当前对话 trace；可空
     * @param name    Agent 名
     * @param agentId Agent id；可空
     * @param input   送给该 Agent 的文本；可空
     * @return 收口句柄；没有 trace 时是空句柄
     */
    public static Handle open(ObsTraceScope trace, String name, Long agentId, String input) {
        if (trace == null) {
            return Handle.none();
        }
        Long parent = trace.implicitParent();
        String shown = name == null || name.isBlank() ? "Agent" : name;
        Long id = trace.open(SpanType.AGENT, shown, agentId, parent, input);
        if (id != null) {
            // 随后的模型步读栈父：先挂到这位子 Agent，而不是继续挂在外壳上
            trace.bindParent(id);
        }
        return new Handle(trace, id, parent);
    }

    /**
     * 一次子 Agent 的开与收。所属域：workflow。空句柄上的调用都是空操作。
     */
    public static final class Handle {

        private final ObsTraceScope trace;
        private final Long id;
        private final Long parent;
        private boolean closed;

        private Handle(ObsTraceScope trace, Long id, Long parent) {
            this.trace = trace;
            this.id = id;
            this.parent = parent;
        }

        /**
         * @return 没有对话 trace 时的空句柄
         */
        static Handle none() {
            return new Handle(null, null, null);
        }

        /**
         * 成功收口，并把栈父还回外壳，下一位子 Agent 仍挂外壳而不是挂在这一位下面。
         */
        public void ok() {
            finish(ObsStatus.OK, "执行完成", null, "执行子 Agent");
        }

        /**
         * 失败收口。文案用调用方已经脱敏过的短句。
         *
         * @param message 失败摘要；可空
         */
        public void fail(String message) {
            finish(ObsStatus.ERROR, message, message, "子 Agent 失败");
        }

        private void finish(ObsStatus status, String output, String error, String brief) {
            if (closed) {
                return;
            }
            closed = true;
            if (trace != null && id != null) {
                SpanClose close = new SpanClose();
                close.setStatus(status);
                close.setOutputText(output);
                close.setErrorSummary(error);
                // 不写 obs.role：这是真专家，标签保持 Agent
                close.setAttrsJson(ObsAttrs.withBrief(null, brief));
                trace.close(id, close);
            }
            if (trace != null) {
                trace.bindParent(parent);
            }
        }
    }
}
