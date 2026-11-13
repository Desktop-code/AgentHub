package com.agenthub.runtime.application;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作台「手动换线」的一次性强制标记。所属域：runtime 的 application。
 *
 * <p>为什么放进程内而不是落库：语义就是「只作用于下一轮」。落库还得再设计一个
 * 「已消费」标记和清理时机，反而多一份状态要维护；而沿用线路本身已经落
 * {@code agent_run_session.sticky_route_key}，即使进程重启丢了强制标记，
 * 下一轮仍会按沿用线路注入提示词，用户的选择不会凭空消失。
 *
 * <p>纪律：
 * <ul>
 *   <li>{@link #take(Long)} 是消费式读取，成功取出即删除，杜绝「一次选择反复生效」；</li>
 *   <li>会话清空 / 取消 / 删除时显式 {@link #clear(Long)}，避免旧标记挂到新对话上；</li>
 *   <li>并发：同一会话同一时刻只允许一轮（单飞闸门），这里只需线程安全容器。</li>
 * </ul>
 */
@Component
public class WorkbenchRouteForceRegistry {

    /** sessionId → 本轮强制命中的 route_key */
    private final Map<Long, String> forced = new ConcurrentHashMap<>();

    /**
     * 登记一次性强制线路。后写覆盖前写：用户连点两次以最后一次为准。
     *
     * @param sessionId 工作台会话主键
     * @param routeKey  目标候选的 route_key
     */
    public void put(Long sessionId, String routeKey) {
        if (sessionId == null || routeKey == null || routeKey.isBlank()) {
            return;
        }
        forced.put(sessionId, routeKey.trim());
    }

    /**
     * 消费式读取：取出即删除，保证只作用一轮。
     *
     * @param sessionId 会话主键
     * @return 强制线路；没有登记时为空
     */
    public Optional<String> take(Long sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(forced.remove(sessionId));
    }

    /**
     * 丢弃未消费的强制标记（清空 / 取消 / 删除会话）。
     *
     * @param sessionId 会话主键
     */
    public void clear(Long sessionId) {
        if (sessionId != null) {
            forced.remove(sessionId);
        }
    }
}
