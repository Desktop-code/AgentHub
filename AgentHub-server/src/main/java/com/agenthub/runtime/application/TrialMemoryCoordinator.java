package com.agenthub.runtime.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.text.UserFacingText;
import com.agenthub.memory.application.ContextWindowFacade;
import com.agenthub.memory.application.LongExtractService;
import com.agenthub.memory.application.MemoryPolicyAppService;
import com.agenthub.memory.application.MemoryRecallFacade;
import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.model.WindowState;
import com.agenthub.memory.domain.service.MemoryTexts;
import com.agenthub.memory.domain.service.SessionTurnSource;
import com.agenthub.runtime.domain.model.MemoryFact;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.TrialPlan;
import com.agenthub.runtime.domain.service.CauseDigest;
import com.agenthub.runtime.domain.service.PromptInjectionHolder;
import com.agenthub.runtime.domain.service.TrialFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 试跑和记忆、窗口的挂钩。所属域：runtime。
 * 用户轮先处理显式记住和窗口，再把摘要、近轮、短期记忆拼进用户消息。
 * 空闲收口后、取消、删除前才走会话级 LONG 抽取。每一轮成功或失败不抽。清空只丢热窗口并清批抽标记。
 * 抽取幂等 / 失败短路在 LongExtractService；本类只拼正文并转发。
 */
@Component
public class TrialMemoryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TrialMemoryCoordinator.class);

    private final MemoryPolicyAppService policies;
    private final ContextWindowFacade windows;
    private final MemoryRecallFacade recall;
    private final LongExtractService extracts;
    private final SessionTurnSource events;

    /**
     * @param policies 策略
     * @param windows  滑动窗口
     * @param recall   SHORT / LONG / AGENT
     * @param extracts 长期偏好
     * @param events   会话结束时重放正文
     */
    public TrialMemoryCoordinator(MemoryPolicyAppService policies, ContextWindowFacade windows,
                                  MemoryRecallFacade recall, LongExtractService extracts, SessionTurnSource events) {
        this.policies = policies;
        this.windows = windows;
        this.recall = recall;
        this.extracts = extracts;
        this.events = events;
    }

    /**
     * 在调用主聊天模型之前准备用户侧上下文。
     *
     * @param session  当前会话
     * @param userText 用户原文
     * @return 含摘要、近轮、短期记忆和当前用户话的文本
     * @throws TrialFailure Redis 或滚动摘要失败
     */
    public String prepareUser(RunSession session, String userText) {
        MemoryPolicy policy = policies.effective(session.getAgentId());
        long userId = session.getUserId() == null ? 0L : session.getUserId();
        try {
            extracts.onExplicit(policy, session.getAgentId(), userId, session.getId(), userText);
            WindowState state = windows.onUser(policy, session.getAgentId(), userId, session.getId(), userText);
            List<MemoryItem> shorts = recall.shorts(policy, session.getAgentId(), session.getId());
            return windows.renderUser(state, userText, shorts);
        } catch (BizException ex) {
            // 该文案会写成 ERROR 事件（用户可见）：只保留业务短句，Redis 地址一类细节不进用户面
            throw new TrialFailure(UserFacingText.safeMessage(ex, "本轮暂时无法继续，请稍后再试。"));
        }
    }

    /**
     * 取本轮要注入系统提示的长期记忆：工作记忆 + 用户偏好。
     * <p>
     * <b>不再自己拼到系统提示尾部</b>：记忆在提示词里的位置是「业务段收口之后、Skill 与工具目录之前」，
     * 由 {@code PromptInjectionComposer} 统一安放。本方法只负责取数与成段，调用方把 block 交给装配器。
     * 原先的尾部追加以导致模型先读完工具清单才看到偏好，「该不该调工具」的判断带不上偏好。
     * <p>
     * 同时带回本轮真正注入的 LONG 条目，供工具必填参数闸门判断「能否用已注入的长期记忆补参」。
     *
     * @param session      会话
     * @return 记忆段正文（无内容时为空串）与已注入记忆；策略关闭长期记忆时两者都为空
     */
    public Augmented recallDurable(RunSession session) {
        MemoryPolicy policy = policies.effective(session.getAgentId());
        long userId = session.getUserId() == null ? 0L : session.getUserId();
        // 虚拟根没有 Agent：长期记忆按 0 号归属读取（等价「无 Agent 记忆」），
        // 不能直接拆 Long 导致空指针；会话内热窗口仍按会话 id 独立。
        long agentId = session.getAgentId() == null ? 0L : session.getAgentId();
        MemoryRecallFacade.Durable durable = recall.durable(policy, agentId, userId);
        StringBuilder block = new StringBuilder();
        if (!durable.agent().isBlank()) {
            block.append("【工作记忆】\n").append(durable.agent());
        }
        if (!durable.pref().isBlank()) {
            if (block.length() > 0) {
                block.append("\n\n");
            }
            block.append("【用户偏好】\n").append(durable.pref());
        }
        // 只把真正拼进提示词的 LONG 转成事实，未注入的条目不得参与补参
        List<MemoryFact> facts = new ArrayList<>();
        for (MemoryItem item : durable.items()) {
            if (item == null) {
                continue;
            }
            facts.add(new MemoryFact(item.getFactKey(), item.getTitle(), item.getContent()));
        }
        return new Augmented(block.toString(), facts);
    }

    /**
     * @param memoryBlock   记忆段正文（{@code recallDurable} 的返回），已带【工作记忆】/【用户偏好】标记
     * @param injectedFacts 本轮已注入的长期记忆
     */
    public record Augmented(String memoryBlock, List<MemoryFact> injectedFacts) {

        public Augmented {
            injectedFacts = injectedFacts == null ? List.of() : List.copyOf(injectedFacts);
        }
    }

    /**
     * 模型调用前放上注入统计，供 LLM span 写入 attrs 和入参头。
     *
     * @param plan 已装配的计划
     */
    public void publishInjection(TrialPlan plan) {
        PromptInjectionHolder.set(plan == null ? null : plan.getInjection());
    }

    /**
     * 助手回复入库之后写入窗口。摘要失败不推翻已经生成的回复，只记日志。
     *
     * @param session 会话
     * @param text    助手正文
     */
    public void afterAssistant(RunSession session, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        MemoryPolicy policy = policies.effective(session.getAgentId());
        long userId = session.getUserId() == null ? 0L : session.getUserId();
        try {
            windows.onAssistant(policy, session.getAgentId(), userId, session.getId(), text);
        } catch (RuntimeException ex) {
            // 窗口写入失败会静默丢一轮上下文：带栈打，才能看出是 Redis 还是摘要模型的问题
            log.warn("助手轮写入窗口失败 sessionId={} type={} cause={}",
                    session.getId(), ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
        }
    }

    /**
     * 取消或删除前抽取长期偏好。成功或失败只是一轮结束，不能在那里抽。
     *
     * @param session 会话（取消时已是 CANCELLED；删除前可能仍是 RUNNING）
     */
    public void onClosed(RunSession session) {
        extractSessionLong(session);
    }

    /**
     * 空闲收口后的批抽入口（与 onClosed 同一条幂等路径）。
     * 调用方须已把会话标终态；本方法不改 status。
     *
     * @param session 已收口或仍 RUNNING 的会话快照
     */
    public void onIdleExtract(RunSession session) {
        extractSessionLong(session);
    }

    /**
     * 按本会话正文跑一次会话级 LONG 抽取（幂等在抽取服务）。
     */
    private void extractSessionLong(RunSession session) {
        if (session == null || session.getAgentId() == null || session.getUserId() == null) {
            log.info("会话级LONG跳过 reason=missing_ids sessionId={} agentId={} userId={}",
                    session == null ? null : session.getId(),
                    session == null ? null : session.getAgentId(),
                    session == null ? null : session.getUserId());
            return;
        }
        MemoryPolicy policy = policies.effective(session.getAgentId());
        String transcript = MemoryTexts.renderTurns(events.replay(session.getId()));
        log.info("会话级LONG进入 onSessionEnd sessionId={} agentId={} userId={} transcriptChars={}",
                session.getId(), session.getAgentId(), session.getUserId(),
                transcript == null ? 0 : transcript.length());
        extracts.onSessionEnd(policy, session.getAgentId(), session.getUserId(), session.getId(), transcript);
    }

    /**
     * 清空会话消息时丢掉热窗口，并清除批抽幂等标记，便于同一会话行之后再抽。
     *
     * @param sessionId 会话
     */
    public void invalidate(long sessionId) {
        try {
            windows.invalidate(sessionId);
        } catch (RuntimeException ex) {
            // 清空失败会导致下一轮继续带着旧上下文：带栈打，否则只能看到一句类名
            log.warn("清空上下文窗口失败 sessionId={} type={} cause={}",
                    sessionId, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
        }
        extracts.clearSessionMarker(sessionId);
    }
}
