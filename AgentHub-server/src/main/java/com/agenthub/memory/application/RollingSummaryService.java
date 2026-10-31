package com.agenthub.memory.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.model.WindowState;
import com.agenthub.memory.domain.service.ContextWindowPlanner;
import com.agenthub.memory.domain.service.MemoryModelClient;
import com.agenthub.memory.domain.service.MemoryTexts;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

/**
 * 窗口触顶时的滚动摘要。所属域：memory。
 * 摘要成功才替换旧轮。模型缺失或调用失败时不裁窗口，调用方不得只做 trim。
 */
@Service
public class RollingSummaryService {

    private static final String INSTRUCTION = """
            把已有会话摘要和下面的旧对话合并成一段新的会话摘要。
            保留未完成事项、关键事实和用户约束。不要写密钥，不要逐字复述工具原文。
            """;

    private final MemoryModelClient models;
    private final SummaryModelResolver resolver;
    private final LongExtractService extracts;

    /**
     * @param models   摘要调用，走计量
     * @param resolver 摘要实例
     * @param extracts 旧段上的顺带抽取，失败不影响摘要
     */
    public RollingSummaryService(MemoryModelClient models, SummaryModelResolver resolver, LongExtractService extracts) {
        this.models = models;
        this.resolver = resolver;
        this.extracts = extracts;
    }

    /**
     * @param state     当前摘要和窗口
     * @param policy    轮数和字符硬顶
     * @param agentId   Agent
     * @param userId    用户，抽取 LONG 时用
     * @param sessionId 会话
     * @return 未触顶时原样返回；触顶且摘要成功时尾部是近轮、摘要槽已更新
     * @throws BizException 需要摘要但模型不可用或没有返回文本
     */
    public WindowState shrink(WindowState state, MemoryPolicy policy, long agentId, long userId, Long sessionId) {
        ContextWindowPlanner.Decision decision = ContextWindowPlanner.decide(
                state.getTurns(), policy.getCtxMaxTurns(), policy.getCtxMaxChars());
        if (!decision.overflow()) {
            return state;
        }
        long modelId = resolver.requireSummary(policy, agentId);
        String source = "已有摘要：\n" + blank(state.getSummary()) + "\n需要并入的旧对话：\n"
                + MemoryTexts.renderTurns(decision.head());
        String next = models.complete(agentId, sessionId, userId, modelId, INSTRUCTION, source);
        if (next == null || next.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "滚动摘要没有返回文本");
        }
        MemoryTexts.rejectSecret(next);
        extracts.onSummary(policy, agentId, userId, sessionId, decision.head());
        WindowState shrunk = new WindowState();
        shrunk.setSummary(next.trim());
        shrunk.setTurns(new ArrayList<>(decision.tail()));
        return shrunk;
    }

    private static String blank(String summary) {
        return summary == null || summary.isBlank() ? "（无）" : summary.trim();
    }
}
