package com.agenthub.runtime.application;

import com.agenthub.runtime.domain.model.WorkflowRunView;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调试 run 的直播卡片快照。所属域：runtime 的 application。
 *
 * <p>职责：把 {@link com.agenthub.runtime.domain.service.WorkflowCardProjector} 在试跑线程上
 * 推出来的整卡快照，按 run 主键暂存在内存里，供调试抽屉短轮询读取。直播仍然只有一条投影，
 * 这里只做「按 run 隔离」的存取，不重算节点、不拼第二张卡。
 *
 * <p>为什么不用 {@code TrialLiveBuffer}：那份缓冲按工作台会话分键，调试试跑没有会话，
 * 也不允许为了调试建会话。这里用 runId 单独一份，和会话直播的既有契约互不影响。
 *
 * <p>有界与降级：
 * <ul>
 *   <li>运行中的条目保留 6 小时，已收口的保留 30 分钟；超时视为「直播已过期」，
 *       前端退回按 run 现算的已落库卡片；</li>
 *   <li>最多 64 条，超出先清最旧的已收口条目，再清最旧的运行中条目，绝不无界增长；</li>
 *   <li>先收口再发布会被忽略，避免取消 / 收尾后又被晚到的一帧「复活」成运行中。</li>
 * </ul>
 *
 * <p>线程：执行线程写、请求线程读；每条 entry 的整份状态一次性替换，读侧永远看到完整快照。
 */
@Component
public class WorkflowDebugLiveCards {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDebugLiveCards.class);

    /** 运行中的 keep-alive：远大于一次调试运行，只防泄漏，不作为业务超时 */
    private static final long RUNNING_TTL_MS = 6 * 60 * 60 * 1000L;

    /** 已收口条目的保留时长：覆盖「关掉调试框、过一会儿再打开」的常见窗口 */
    private static final long FINISHED_TTL_MS = 30 * 60 * 1000L;

    /** 单实例最多保留的 run 数：调试是控制台单用户场景，64 条足够覆盖并发试跑 */
    private static final int MAX_ENTRIES = 64;

    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();

    /**
     * 开跑 / 续跑时登记一条直播。
     *
     * @param runId    运行主键
     * @param tenantId 运行所属租户；读取时做同租户校验
     * @param view     首帧出来之前的卡片；一般为空，前端会继续保留已经画出的卡
     * @param rows     当前节点行；会复制一份，避免执行线程后续修改影响已发布快照
     */
    public void begin(Long runId, Long tenantId, WorkflowRunView view, List<WorkflowNodeRun> rows) {
        if (runId == null) {
            return;
        }
        State state = new State(tenantId, view, copyRows(rows),
                view == null ? null : view.status(), null, true, System.currentTimeMillis());
        entries.computeIfAbsent(runId, id -> new Entry()).state = state;
        evict();
    }

    /**
     * 发布一帧运行中的整卡快照。
     *
     * <p>只更新仍在运行的条目；已经收口的 run 不再接收，避免迟到帧把终态改回运行中。
     *
     * @param runId 运行主键
     * @param view  与首页同形的整卡视图
     * @param rows  这一帧对应的节点行快照
     */
    public void publish(Long runId, WorkflowRunView view, List<WorkflowNodeRun> rows) {
        if (runId == null || view == null) {
            return;
        }
        Entry entry = entries.get(runId);
        if (entry == null) {
            return;
        }
        synchronized (entry) {
            State current = entry.state;
            if (current == null || !current.running) {
                return;
            }
            entry.state = new State(current.tenantId, view, copyRows(rows), view.status(),
                    current.errorSummary, true, System.currentTimeMillis());
        }
    }

    /**
     * 收口一条直播：写入最终卡片与 run 的终态，之后不再接收新帧。
     *
     * @param runId       运行主键
     * @param view        最终卡片；空时保留最后一帧，避免把已流出的内容清掉
     * @param rows        最终节点行
     * @param status      库里读回的 run 状态
     * @param errorSummary 失败 / 取消摘要；可空
     */
    public void finish(Long runId, WorkflowRunView view, List<WorkflowNodeRun> rows,
                       String status, String errorSummary) {
        if (runId == null) {
            return;
        }
        Entry entry = entries.get(runId);
        if (entry == null) {
            return;
        }
        synchronized (entry) {
            State current = entry.state;
            WorkflowRunView shown = view != null ? view : current == null ? null : current.view;
            String shownStatus = status == null || status.isBlank()
                    ? shown == null ? null : shown.status() : status;
            entry.state = new State(current == null ? null : current.tenantId, shown, copyRows(rows),
                    shownStatus, errorSummary, false, System.currentTimeMillis());
        }
        evict();
    }

    /**
     * 丢弃一条直播（挂载失败 / 运行被删）。
     *
     * <p>与 {@link #finish} 不同：直接移除条目，让读侧看到 {@code live=false} 并退回详情，
     * 而不是继续读一份永远不会再更新的运行中状态。
     *
     * @param runId 运行主键
     */
    public void discard(Long runId) {
        if (runId == null) {
            return;
        }
        entries.remove(runId);
    }

    /**
     * 读取一次直播快照。没有、已过期或 run 已收口很久时返回空，调用方退回详情现算。
     *
     * @param runId 运行主键
     * @return 快照；不存在 / 过期时为 {@code null}
     */
    public Snapshot snapshot(Long runId) {
        if (runId == null) {
            return null;
        }
        Entry entry = entries.get(runId);
        if (entry == null) {
            return null;
        }
        State state = entry.state;
        if (state == null) {
            entries.remove(runId, entry);
            return null;
        }
        if (expired(state)) {
            entries.remove(runId, entry);
            return null;
        }
        return new Snapshot(state.tenantId, state.view, state.rows, state.status, state.errorSummary);
    }

    /**
     * 上一次已发布的整卡视图；用于续跑时接上正文。
     *
     * @param runId 运行主键
     * @return 视图；没有直播时为空
     */
    public WorkflowRunView viewOf(Long runId) {
        Snapshot snapshot = snapshot(runId);
        return snapshot == null ? null : snapshot.view();
    }

    /**
     * 上一次已流出的正文；续跑投影用它避免把上一段问句整段重来。
     *
     * @param runId 运行主键
     * @return 正文；没有直播或正文为空时为空串
     */
    public String answerOf(Long runId) {
        WorkflowRunView view = viewOf(runId);
        return view == null || view.answer() == null ? "" : view.answer();
    }

    /**
     * 按 TTL 与容量清理旧条目。写路径调用，不需要独立定时任务。
     */
    private void evict() {
        entries.entrySet().removeIf(item -> item.getValue().state == null
                || expired(item.getValue().state));
        if (entries.size() <= MAX_ENTRIES) {
            return;
        }
        List<Map.Entry<Long, Entry>> candidates = new ArrayList<>(entries.entrySet());
        candidates.sort(Comparator.comparingLong(item -> item.getValue().state == null
                ? 0L : item.getValue().state.updatedAt()));
        // 先清已收口的旧条目；仍在跑的 run 尽量保留，最后才按容量兜底
        for (Map.Entry<Long, Entry> candidate : candidates) {
            if (entries.size() <= MAX_ENTRIES) {
                break;
            }
            State state = candidate.getValue().state;
            if (state != null && !state.running) {
                entries.remove(candidate.getKey(), candidate.getValue());
            }
        }
        for (Map.Entry<Long, Entry> candidate : candidates) {
            if (entries.size() <= MAX_ENTRIES) {
                break;
            }
            entries.remove(candidate.getKey(), candidate.getValue());
        }
        if (entries.size() > MAX_ENTRIES) {
            log.warn("调试直播条目超过上限，已按最旧淘汰 size={}", entries.size());
        }
    }

    /**
     * @param state 条目状态
     * @return true 表示已超过保留时长
     */
    private static boolean expired(State state) {
        long ttl = state.running ? RUNNING_TTL_MS : FINISHED_TTL_MS;
        return System.currentTimeMillis() - state.updatedAt > ttl;
    }

    /**
     * 复制节点行：发布快照必须与执行线程后续修改隔离。
     *
     * @param rows 节点行；可空
     * @return 新列表；不共享可变行对象
     */
    private static List<WorkflowNodeRun> copyRows(List<WorkflowNodeRun> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<WorkflowNodeRun> copies = new ArrayList<>(rows.size());
        for (WorkflowNodeRun row : rows) {
            if (row == null) {
                continue;
            }
            copies.add(copyOf(row));
        }
        return List.copyOf(copies);
    }

    /**
     * 单行复制，字段与画布高亮 / 卡片 status 口径一致。
     *
     * @param row 原行
     * @return 新行
     */
    private static WorkflowNodeRun copyOf(WorkflowNodeRun row) {
        WorkflowNodeRun copy = new WorkflowNodeRun();
        copy.setId(row.getId());
        copy.setRunId(row.getRunId());
        copy.setNodeId(row.getNodeId());
        copy.setNodeCode(row.getNodeCode());
        copy.setNodeType(row.getNodeType());
        copy.setAttempt(row.getAttempt());
        copy.setStatus(row.getStatus());
        copy.setInputJson(row.getInputJson());
        copy.setOutputJson(row.getOutputJson());
        copy.setErrorSummary(row.getErrorSummary());
        copy.setReplyText(row.getReplyText());
        copy.setSelectedEdges(row.getSelectedEdges());
        copy.setStartTime(row.getStartTime());
        copy.setEndTime(row.getEndTime());
        return copy;
    }

    /** 一条 run 的可变槽位；整份状态一次性替换，读侧不加锁也能拿到完整快照。 */
    private static final class Entry {
        private volatile State state;
    }

    /**
     * 不可变快照内容。
     *
     * @param tenantId     租户
     * @param view         整卡视图；可为空
     * @param rows         节点行
     * @param status       run 状态
     * @param errorSummary 失败 / 取消摘要
     * @param running      是否仍在执行
     * @param updatedAt    最后更新时间（毫秒）
     */
    private record State(Long tenantId, WorkflowRunView view, List<WorkflowNodeRun> rows,
                         String status, String errorSummary, boolean running, long updatedAt) {
    }

    /**
     * 读侧快照。与运行中的可变状态解耦。
     *
     * @param tenantId     租户
     * @param view         整卡视图；可为空
     * @param rows         节点行
     * @param status       run 状态
     * @param errorSummary 失败 / 取消摘要
     */
    public record Snapshot(Long tenantId, WorkflowRunView view, List<WorkflowNodeRun> rows,
                           String status, String errorSummary) {
    }
}
