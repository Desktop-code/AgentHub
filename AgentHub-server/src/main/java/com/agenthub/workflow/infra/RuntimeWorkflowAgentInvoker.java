package com.agenthub.workflow.infra;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.runtime.application.TrialBindingLoader;
import com.agenthub.runtime.domain.model.AgentChoice;
import com.agenthub.runtime.domain.model.ChoiceCapture;
import com.agenthub.runtime.domain.model.TrialPlan;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.service.ChoiceCaptureHolder;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.domain.service.WorkflowAgentFileScope;
import com.agenthub.runtime.domain.service.WorkbenchChoiceJson;
import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import com.agenthub.workflow.domain.WorkflowNodeFailure;
import com.agenthub.workflow.domain.service.WorkflowAgentInvoker;
import com.agenthub.workflow.domain.service.WorkflowOutcomeTexts;
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
        return invokeForReply(agentId, message, userId, runId).text();
    }

    /**
     * {@inheritDoc}
     * <p>装配口径与原来一致（选项能力由 Agent 自身配置决定），节点编码只用于把过程挂到执行卡片上。
     */
    @Override
    public String invoke(Long agentId, String message, long userId, long runId, String nodeCode) {
        return doInvoke(agentId, message, userId, runId, nodeCode, true).text();
    }

    /**
     * 带可点选项的一次执行（「等用户回复」的询问节点用）。
     *
     * <p>选项能力复用单专家那一套：装配时打开 {@code choiceEnabled}、请求线程放采集槽、
     * Agent 调平台内置 {@code agenthub_choice}（或后端整理兜底），最后把采集槽里的结构
     * 序列化成与 CHOICE 事件相同的 JSON 交给工作流域透传。
     *
     * <p>为什么在这里放/清采集槽：它是请求级 ThreadLocal，叶子内联执行时同线程可见；
     * 本方法在引擎线程上同步执行，因此用完必须在 finally 清掉，避免串到下一节点。
     *
     * @param agentId 目标 Agent 主键
     * @param message 用户文本
     * @param userId  平台用户
     * @param runId   工作流 run
     * @return 正文 + 选项 JSON（没有选项时为空串）
     */
    @Override
    public Reply invokeForReply(Long agentId, String message, long userId, long runId) {
        return doInvoke(agentId, message, userId, runId, "", true);
    }

    /**
     * {@inheritDoc}
     * <p>续跑时的询问节点同样按节点编码回传过程，选项仍走原有采集槽。
     */
    @Override
    public Reply invokeForReply(Long agentId, String message, long userId, long runId, String nodeCode) {
        return doInvoke(agentId, message, userId, runId, nodeCode, true);
    }

    /**
     * 两种签名共用的执行体。
     *
     * @param agentId      目标 Agent 主键
     * @param message      用户文本
     * @param userId       平台用户
     * @param runId        工作流 run（沙箱兜底会话）
     * @param nodeCode     当前节点编码；空串表示不挂节点内过程
     * @param choiceEnabled 是否装配可点选项工具（只有等待回复的询问节点为 true）
     * @return 正文 + 选项 JSON
     */
    private Reply doInvoke(Long agentId, String message, long userId, long runId,
                           String nodeCode, boolean choiceEnabled) {
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
        // 放采集槽：只有本轮装配打开 choiceEnabled 时工具才会真的安装
        ChoiceCapture capture = ChoiceCaptureHolder.begin();
        try {
            TrialPlan plan = loader.loadReact(agent, message, null, choiceEnabled);
            /*
             * 工作台跑工作流时，沙箱必须落在工作台会话目录，下载地址才对得上。
             * 没有打开文件范围时仍用 runId，工作流页单独运行的行为与原来一致。
             * 进度按节点编码回传：文件照旧走工作台文件帧，思考 / 工具 / 运行中正文
             * 交给执行卡片挂到对应节点，不再堆到整张卡下面。
             */
            long sandboxSession = WorkflowAgentFileScope.sandboxSessionId(runId);
            TrialTurn turn = react.execute(plan, message, userId, sandboxSession,
                    WorkflowAgentFileScope.nodeProgress(nodeCode));
            WorkflowAgentFileScope.collect(turn == null ? null : turn.getFiles());
            WorkflowAgentFileScope.collectForNode(nodeCode, turn == null ? null : turn.getFiles());
            String text = turn == null ? null : turn.getAssistantText();
            if (text == null || text.isBlank()) {
                consoleAgent.fail("Agent「" + agent.getName() + "」没有返回文本");
                throw new WorkflowNodeFailure("Agent「" + agent.getName() + "」没有返回文本");
            }
            consoleAgent.ok();
            // 采集槽里的结构由 ReAct 执行器收口（工具直出 / 后端整理兜底），这里只做序列化
            AgentChoice choice = capture.isEligible() ? capture.choice() : null;
            return new Reply(text, WorkbenchChoiceJson.write(choice));
        } catch (TrialFailure ex) {
            /*
             * 子 Agent 的预算 / 超时原文是内部口径（含「撞限」与毫秒、键名），
             * 这里先收成对话可见的一句话，再作为节点失败摘要往上抛。
             * 内部原文仍写 trace / 日志，便于排障。
             */
            String visible = WorkflowOutcomeTexts.userText(ex.getMessage());
            consoleAgent.fail(visible);
            // 已是完整收口话术（超时 / 上限）就直接用，不再套一层 Agent 前缀
            String nodeMessage = WorkflowOutcomeTexts.isStopNotice(visible)
                    ? visible
                    : "Agent「" + agent.getName() + "」执行失败：" + visible;
            throw new WorkflowNodeFailure(nodeMessage, ex);
        } catch (WorkflowNodeFailure ex) {
            throw ex;
        } catch (RuntimeException ex) {
            consoleAgent.fail(ex.getMessage());
            throw ex;
        } finally {
            // 请求级槽：不清会把上一节点的选项带给下一节点
            ChoiceCaptureHolder.clear();
        }
    }
}
