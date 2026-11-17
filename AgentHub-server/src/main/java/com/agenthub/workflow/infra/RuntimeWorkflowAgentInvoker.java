package com.agenthub.workflow.infra;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.runtime.application.TrialBindingLoader;
import com.agenthub.runtime.domain.model.TrialPlan;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import com.agenthub.workflow.domain.WorkflowNodeFailure;
import com.agenthub.workflow.domain.service.WorkflowAgentInvoker;
import org.springframework.stereotype.Component;

/**
 * AGENT 节点执行实现。所属域：workflow 的 infra。
 * <p>复用现网试跑链路（TrialBindingLoader + ReActTrialExecutor），
 * 不新开第二套 ReAct 循环；目标 Agent 的 Auto / 反思 / 超时等配置全部继承（决议 W6）。
 * <p>禁用 ROUTER：画布已能表达扇出（决议 W2），这里再兜一次，防止历史数据绕过校验。
 */
@Component
public class RuntimeWorkflowAgentInvoker implements WorkflowAgentInvoker {

    private final AgentDefRepository agents;
    private final TrialBindingLoader loader;
    private final ReActTrialExecutor react;

    /**
     * @param agents Agent 定义仓储
     * @param loader 试跑装配（模型 / 工具 / 提示词）
     * @param react  官方 ReAct 执行器
     */
    public RuntimeWorkflowAgentInvoker(AgentDefRepository agents, TrialBindingLoader loader,
                                       ReActTrialExecutor react) {
        this.agents = agents;
        this.loader = loader;
        this.react = react;
    }

    /**
     * {@inheritDoc}
     * <p>会话侧标识直接用 runId：现网 MeterScope / 状态键只要求唯一，
     * 用 runId 能把计费与排障串到同一次工作流执行。
     */
    @Override
    public String invoke(Long agentId, String message, long userId, long runId) {
        AgentDef agent = agents.findById(agentId)
                .orElseThrow(() -> new WorkflowNodeFailure("绑定的 Agent 不存在或已删除"));
        if (!RecordStatus.isEnabled(agent.getStatus())) {
            throw new WorkflowNodeFailure("绑定的 Agent「" + agent.getName() + "」已停用");
        }
        if ("ROUTER".equalsIgnoreCase(agent.getRunMode())) {
            throw new WorkflowNodeFailure("AGENT 节点不允许绑定 ROUTER Agent，请改绑叶子 Agent 或用画布并行");
        }
        // 对话 trace 已绑定时，先在外壳下记下这位子 Agent，模型步才不会直接挂在外壳上。
        // 工作流页单独运行没有对话 trace，句柄为空，节点仍只在工作流自己的 trace 里。
        WorkflowConsoleAgentSpan.Handle consoleAgent = WorkflowConsoleAgentSpan.open(
                ObsTraceHolder.current(), agent.getName(), agent.getId(), message);
        try {
            TrialPlan plan = loader.loadReact(agent, message);
            TrialTurn turn = react.execute(plan, message, userId, runId);
            String text = turn == null ? null : turn.getAssistantText();
            if (text == null || text.isBlank()) {
                consoleAgent.fail("Agent「" + agent.getName() + "」没有返回文本");
                throw new WorkflowNodeFailure("Agent「" + agent.getName() + "」没有返回文本");
            }
            consoleAgent.ok();
            return text;
        } catch (TrialFailure ex) {
            // TrialFailure 的文案已脱敏，可直接作为节点错误摘要
            consoleAgent.fail(ex.getMessage());
            throw new WorkflowNodeFailure("Agent「" + agent.getName() + "」执行失败：" + ex.getMessage(), ex);
        } catch (WorkflowNodeFailure ex) {
            throw ex;
        } catch (RuntimeException ex) {
            consoleAgent.fail(ex.getMessage());
            throw ex;
        }
    }
}
