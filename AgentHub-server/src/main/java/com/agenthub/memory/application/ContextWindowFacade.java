package com.agenthub.memory.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.memory.domain.model.ContextTurn;
import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.model.WindowState;
import com.agenthub.memory.domain.service.ContextWindowStore;
import com.agenthub.memory.domain.service.SessionTurnSource;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话上下文窗口。所属域：memory。
 * 热路径是 Redis，miss 时从会话事件回补。触顶走滚动摘要，禁止只删不总结。
 */
@Component
public class ContextWindowFacade {

    private final ContextWindowStore store;
    private final SessionTurnSource events;
    private final RollingSummaryService summaries;

    /**
     * @param store      窗口和摘要槽
     * @param events     miss 回补
     * @param summaries  触顶摘要
     */
    public ContextWindowFacade(ContextWindowStore store, SessionTurnSource events, RollingSummaryService summaries) {
        this.store = store;
        this.events = events;
        this.summaries = summaries;
    }

    /**
     * 用户轮写入事件之后调用。把本轮放进窗口，超限则先摘要再留下近轮。
     *
     * @param policy    窗口策略
     * @param agentId   Agent
     * @param userId    用户
     * @param sessionId 会话
     * @param text      用户原文
     * @return 摘要之后的窗口，含本轮
     */
    public WindowState onUser(MemoryPolicy policy, long agentId, long userId, long sessionId, String text) {
        WindowState state = load(policy, agentId, userId, sessionId);
        state.getTurns().add(ContextTurn.of("user", text));
        return shrinkAndSave(policy, agentId, userId, sessionId, state);
    }

    /**
     * 助手回复落库后追加。摘要失败时仍把这一轮留在窗口里，避免把刚生成的回复丢掉。
     *
     * @param policy    策略
     * @param agentId   Agent
     * @param userId    用户
     * @param sessionId 会话
     * @param text      助手正文
     */
    public void onAssistant(MemoryPolicy policy, long agentId, long userId, long sessionId, String text) {
        WindowState state = load(policy, agentId, userId, sessionId);
        state.getTurns().add(ContextTurn.of("assistant", text));
        try {
            shrinkAndSave(policy, agentId, userId, sessionId, state);
        } catch (BizException ex) {
            saveQuietly(sessionId, state, policy.getCtxIdleTtlSec());
            throw ex;
        }
    }

    /**
     * 组进模型的用户侧正文：摘要、近轮（不含刚追加的当前用户话）、短期记忆、当前用户话。
     *
     * @param state   已含当前用户轮的窗口
     * @param current 当前用户话
     * @param shorts  短期记忆
     * @return 交给模型的用户消息
     */
    public String renderUser(WindowState state, String current, List<MemoryItem> shorts) {
        StringBuilder text = new StringBuilder();
        if (state.getSummary() != null && !state.getSummary().isBlank()) {
            text.append("【会话摘要】\n").append(state.getSummary().trim()).append("\n\n");
        }
        String recent = recentWithoutCurrent(state.getTurns(), current);
        if (!recent.isBlank()) {
            text.append("【最近对话】\n").append(recent).append('\n');
        }
        if (shorts != null && !shorts.isEmpty()) {
            text.append("【短期记忆】\n");
            for (MemoryItem item : shorts) {
                text.append("- ");
                if (item.getTitle() != null && !item.getTitle().isBlank()) {
                    text.append(item.getTitle().trim()).append('：');
                }
                text.append(item.getContent() == null ? "" : item.getContent().trim()).append('\n');
            }
            text.append('\n');
        }
        text.append("【当前用户】\n").append(current == null ? "" : current.trim());
        return text.toString();
    }

    /**
     * 清空或删除会话时丢掉热数据，下次从事件重放。
     *
     * @param sessionId 会话
     */
    public void invalidate(long sessionId) {
        try {
            store.delete(sessionId);
        } catch (RuntimeException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Redis 不可用，上下文窗口写入失败");
        }
    }

    private WindowState load(MemoryPolicy policy, long agentId, long userId, long sessionId) {
        try {
            var hit = store.readTurns(sessionId);
            if (hit.isPresent()) {
                WindowState state = new WindowState();
                state.setTurns(new ArrayList<>(hit.get()));
                state.setSummary(store.readSummary(sessionId));
                return state;
            }
        } catch (RuntimeException ex) {
            throw window(ex, "Redis 不可用，上下文窗口读取失败");
        }
        WindowState replayed = new WindowState();
        replayed.setTurns(new ArrayList<>(events.replay(sessionId)));
        try {
            replayed.setSummary(store.readSummary(sessionId));
        } catch (RuntimeException ex) {
            replayed.setSummary("");
        }
        // 回补出来的历史如果已经触顶，先摘要再交给本轮使用。失败则保持 key 缺失，事件仍是真源
        WindowState shrunk = summaries.shrink(replayed, policy, agentId, userId, sessionId);
        save(sessionId, shrunk, policy.getCtxIdleTtlSec());
        return shrunk;
    }

    private WindowState shrinkAndSave(MemoryPolicy policy, long agentId, long userId, long sessionId, WindowState state) {
        try {
            WindowState shrunk = summaries.shrink(state, policy, agentId, userId, sessionId);
            save(sessionId, shrunk, policy.getCtxIdleTtlSec());
            return shrunk;
        } catch (BizException ex) {
            // 摘要失败时把未裁剪的窗口留下，禁止只 trim
            saveQuietly(sessionId, state, policy.getCtxIdleTtlSec());
            throw ex;
        }
    }

    private void save(long sessionId, WindowState state, Integer ttl) {
        try {
            store.save(sessionId, state.getSummary(), state.getTurns(), ttl == null ? 7200 : ttl);
        } catch (RuntimeException ex) {
            throw window(ex, "Redis 不可用，上下文窗口写入失败");
        }
    }

    private void saveQuietly(long sessionId, WindowState state, Integer ttl) {
        try {
            store.save(sessionId, state.getSummary(), state.getTurns(), ttl == null ? 7200 : ttl);
        } catch (RuntimeException ex) {
            try {
                store.delete(sessionId);
            } catch (RuntimeException ignored) {
                // 留给下一次 miss 从事件回补
            }
        }
    }

    private String recentWithoutCurrent(List<ContextTurn> turns, String current) {
        if (turns == null || turns.isEmpty()) {
            return "";
        }
        int end = turns.size();
        ContextTurn last = turns.get(turns.size() - 1);
        if (last != null && "user".equalsIgnoreCase(last.getRole()) && current != null
                && current.equals(last.getText())) {
            end = turns.size() - 1;
        }
        return com.agenthub.memory.domain.service.MemoryTexts.renderTurns(turns.subList(0, end)).trim();
    }

    private BizException window(RuntimeException ex, String message) {
        if (ex instanceof BizException biz) {
            return biz;
        }
        if (ex instanceof DataAccessException) {
            return new BizException(ErrorCode.BAD_REQUEST, message);
        }
        return new BizException(ErrorCode.BAD_REQUEST, message);
    }
}
