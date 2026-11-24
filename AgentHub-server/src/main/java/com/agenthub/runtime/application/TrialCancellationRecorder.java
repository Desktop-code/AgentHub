package com.agenthub.runtime.application;

import com.agenthub.runtime.api.dto.EventResp;
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.domain.service.CauseDigest;
import com.agenthub.runtime.domain.service.WorkflowRunJson;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用户停止后的「取消补写」。所属域：runtime（会话运行）。
 *
 * <p>从 {@code TrialRunAppService} 搬出，职责只有一件事：执行线程收尾时，把内存快照里
 * 已经出现的过程、正文与工作流卡片按中断终态补落库，并补一条「已中断」闭环。
 *
 * <p>必须保持不变的口径：
 * <ul>
 *   <li>只在 {@link TrialLiveBuffer#cancelled(long)} 为真时执行；未取消直接返回；</li>
 *   <li>按事件类型去重（USER / VISION / PREPROCESS 与 ERROR 跳过）；工作流卡片按编码单独处理：
 *       库里已是终态则保留，仍是 RUNNING 则用库里最新卡片封口再写；</li>
 *   <li>闭环说明「已中断」只在库里本段没有中断闭环时补一条；</li>
 *   <li>任何运行时异常只记日志、不外抛：停止已经生效，补写失败最多回放少一段过程。</li>
 * </ul>
 */
public class TrialCancellationRecorder {

    private static final Logger log = LoggerFactory.getLogger(TrialCancellationRecorder.class);

    /** 进行中一轮的内存快照；取消封口由调用方先做（sealCancelled）。 */
    private final TrialLiveBuffer live;

    /** 已落库事件：用于按类型 / 工作流卡片判重。 */
    private final RunEventRepository events;

    /** 事件写入器：补写沿用与执行线程相同的落库口径。 */
    private final TrialEventWriter writer;

    /**
     * @param live   内存快照
     * @param events 已落库事件仓储
     * @param writer 事件写入器
     */
    public TrialCancellationRecorder(TrialLiveBuffer live, RunEventRepository events, TrialEventWriter writer) {
        this.live = live;
        this.events = events;
        this.writer = writer;
    }

    /**
     * 用户停止后，把内存快照里已经出现的过程、正文与工作流卡片按中断终态补落库。
     *
     * <p>为什么必须补：引擎的思考 / 工具 / 工作流帧只在 SSE 与内存快照里，整轮落库只在
     * 正常结束时发生；中断路径原本只写一条「已中断」，刷新后过程与工作流进度会整块消失，
     * 或者只剩最后一张 RUNNING 快照。这里把快照（已由 {@link TrialLiveBuffer#sealCancelled(Long)} 封成终态）
     * 写成与直播一致的事件。
     *
     * <p>只在执行线程的 finally 里调用：此时本轮自己的事件已经写完，序号用
     * {@link RunEventRepository#nextSeq(Long)} 重新取，避开执行过程中提前算好的局部 seq。
     * 补写按事件类型去重：除工作流外，库里已有的类型（例如完整路由后的正文 / 思考）不重复补；
     * 工作流卡片用封口后的快照再写一条，回放按同编码原位覆盖停止前的 RUNNING 帧；
     * 最后兜底写一条「已中断」闭环。
     *
     * @param sessionId 会话主键
     */
    public void persistIfCancelled(long sessionId) {
        if (!live.cancelled(sessionId)) {
            return;
        }
        try {
            List<EventResp> snapshot = live.snapshot(sessionId);
            if (snapshot.isEmpty()) {
                return;
            }
            // 快照第一条就是本轮起点（USER 或批准续跑的起始 seq），减一得到「本轮之前」的边界
            Integer firstSeq = snapshot.get(0).getSeq();
            int startSeq = firstSeq == null ? 0 : Math.max(0, firstSeq - 1);
            List<RunEvent> persisted = events.listBySessionId(sessionId);
            Set<String> seen = new HashSet<>();
            Set<String> persistedTypes = new HashSet<>();
            // 库里已有的工作流卡片：按编码 / 名字找同一张，停止补写时优先保留库里更新的那份
            Map<String, RunEvent> persistedWorkflows = new HashMap<>();
            boolean closure = false;
            for (RunEvent row : persisted) {
                if (row == null || row.getSeq() == null || row.getSeq() <= startSeq) {
                    continue;
                }
                String type = row.getEventType();
                if (isInterruptClosure(type, row.getContentText(), row.getSummary())) {
                    closure = true;
                }
                if (type != null) {
                    persistedTypes.add(type.trim().toLowerCase());
                }
                if ("workflow".equalsIgnoreCase(type)) {
                    String cardKey = WorkflowRunJson.cardKey(row.getContentText());
                    if (!cardKey.isEmpty()) {
                        persistedWorkflows.put(cardKey, row);
                    }
                }
                seen.add(eventKey(type, row.getContentText(), row.getSummary()));
            }
            /*
             * 按「类型」补缺，而不是看到一条时间线就整份放弃：
             * 计划模式被停止时，库里可能已经落了 PLAN / STEP 帧，但工作流卡片只在快照里；
             * 若库里已经有某一类事件（例如完整路由后的 REASONING / ASSISTANT），再补同类型会重复。
             * 因此除工作流外，某类事件一旦在库里出现就跳过该类的快照。
             * 工作流例外：库里那条可能还是停止前的 RUNNING，必须再写一条封口后的 CANCELLED，
             * 前端回放按同编码原位覆盖，终态以后写的为准。
             */
            int seq = events.nextSeq(sessionId);
            for (EventResp item : snapshot) {
                String type = item.getEventType();
                if (isTurnStartType(type) || type == null) {
                    continue;
                }
                String code = type.trim().toLowerCase();
                // 闭环说明统一在末尾补一条，避免快照里的提示与库里已有的重复
                if ("error".equals(code)) {
                    continue;
                }
                /*
                 * 工作流卡片单独处理：库里已有同编码 / 同名卡片时，
                 * - 已是终态：保留库里那份（更完整），不再用更早的直播快照覆盖；
                 * - 仍是 RUNNING 等非终态：用库里那份封口再写一条，保留最新节点 / 正文；
                 * 库里没有这张卡时才用直播快照的封口版本。
                 * 其它类型仍按类型去重，避免把同一段正文 / 思考重复补一遍。
                 */
                boolean sealedWorkflow = "workflow".equals(code);
                if (sealedWorkflow) {
                    RunEvent dbCard = persistedWorkflows.get(WorkflowRunJson.cardKey(item.getContentText()));
                    if (dbCard != null) {
                        String dbStatus = WorkflowRunJson.statusOf(dbCard.getContentText());
                        // 库里已经是终态：保留库里最新正文 / 节点，不再用更早的直播快照覆盖
                        if (WorkflowRunJson.terminal(dbStatus)) {
                            continue;
                        }
                        /*
                         * 库里仍是停止前的非终态（典型是收尾前批量落库的 RUNNING 帧）：
                         * 用库里最新卡片封口再写一条，保留最后一次节点与正文；
                         * 直播快照可能更早，直接拿它覆盖会丢收尾内容。
                         */
                        String sealed = WorkflowRunJson.cancelled(dbCard.getContentText());
                        if (sealed != null && !sealed.isBlank() && !sealed.equals(dbCard.getContentText())) {
                            writer.append(sessionId, seq++, EventType.WORKFLOW,
                                    dbCard.getSummary(), sealed, null, null);
                            continue;
                        }
                    }
                } else if (persistedTypes.contains(code)) {
                    continue;
                }
                String key = eventKey(type, item.getContentText(), item.getSummary());
                if (seen.contains(key)) {
                    continue;
                }
                EventType kind = eventTypeOf(type);
                if (kind == null) {
                    continue;
                }
                writer.append(sessionId, seq++, kind, item.getSummary(), item.getContentText(), null, null);
                seen.add(key);
                persistedTypes.add(code);
            }
            if (!closure) {
                // 闭环说明：刷新后 hasOpenTurn 看到它就停止续订，页面落在中断终态
                writer.append(sessionId, events.nextSeq(sessionId), EventType.ERROR, "已中断", null, null);
            }
        } catch (RuntimeException ex) {
            // 补写失败不能反噬主链路：停止本身已经生效，最多回放少一段过程
            log.warn("停止后补写中断终态失败 sessionId={} type={} cause={}",
                    sessionId, ex.getClass().getSimpleName(), CauseDigest.of(ex));
        }
    }

    /** 本轮起点事件：USER / VISION / PREPROCESS 在运行过程中已落库，补快照时跳过。 */
    private static boolean isTurnStartType(String type) {
        String code = type == null ? "" : type.trim().toLowerCase();
        return "user".equals(code) || "vision".equals(code) || "preprocess".equals(code);
    }

    /** 事件串码 → 枚举；未知类型不补写。 */
    private static EventType eventTypeOf(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (EventType type : EventType.values()) {
            if (type.code().equalsIgnoreCase(code.trim())) {
                return type;
            }
        }
        return null;
    }

    /** 是否为中断闭环说明（正文或摘要里落过「已中断 / 已停止 / 已取消」）。 */
    private static boolean isInterruptClosure(String type, String content, String summary) {
        if (type == null || !"error".equalsIgnoreCase(type.trim())) {
            return false;
        }
        String text = ((content == null ? "" : content) + " " + (summary == null ? "" : summary)).trim();
        return text.contains("已中断") || text.contains("已停止") || text.contains("已取消");
    }

    /** 去重键：类型 + 正文 + 摘要，避免与执行线程已经落过的同一事件重复。 */
    private static String eventKey(String type, String content, String summary) {
        return (type == null ? "" : type.trim().toLowerCase())
                + "|" + (content == null ? "" : content)
                + "|" + (summary == null ? "" : summary);
    }
}
