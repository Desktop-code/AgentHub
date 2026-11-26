package com.agenthub.runtime.application;

import com.agenthub.runtime.api.dto.EventResp;
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.WorkflowRunView;
import com.agenthub.runtime.domain.service.WorkflowCardProjector;
import com.agenthub.runtime.domain.service.WorkflowRunJson;
import com.agenthub.workflow.application.WorkflowRunAppService;
import com.agenthub.workflow.domain.enums.WorkflowNodeRunStatus;
import com.agenthub.workflow.domain.model.WorkflowEdge;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 刷新回放时把工作流卡片的节点产出补回来。所属域：runtime 的 application。
 *
 * <p>为什么需要它：落库事件只存卡片骨架（{@link WorkflowRunJson#compact}），节点正文不再
 * 复制进 {@code agent_run_event}——那份 JSON 曾经因为多节点长代码顶过 32K 截断线，
 * 变成半截字符串，刷新后整张卡消失、页面退回上一轮「等待回复」。
 *
 * <p>补全口径与实时一致：
 * <ul>
 *   <li>节点正文由 {@link WorkflowCardProjector#nodeText} 抽取（与直播同一个方法），
 *       真源是 {@code workflow_node_run.output_json}；</li>
 *   <li>用户回复取 {@code workflow_node_run.reply_text}（续跑时落库）；</li>
 *   <li><b>状态按库校准</b>：骨架里停在「等待回复 / 未开始」、库里已经往前走的节点，
 *       改成库里的状态与说明；没有任何节点还在等时清掉卡片级的过期 {@code choice}。
 *       否则刷新这一拍会出现「答复被藏、选项照画」——用户明明选过语言，页面又让他选一次；</li>
 *   <li>只补「带指针」的骨架（{@code v >= 2} 且有 {@code runId}），旧全量卡原样返回，
 *       不改变历史卡片的展示；同一 run 只校准最后一张骨架，历史等待卡保持原样供前端接管；</li>
 * </ul>
 *
 * <p>边界：
 * <ul>
 *   <li><b>只读</b>：不写库、不改 run 状态、不触发调度；</li>
 *   <li><b>不丢事件</b>：补全失败只记日志，保留骨架——前端仍能画出「已结束」的卡片，
 *       不会退回成询问态；</li>
 *   <li><b>有界</b>：单节点正文按总预算分配硬顶，节点再多也不会把一次刷新拉成几十万字符；</li>
 *   <li><b>不串身份</b>：只按卡片里的 {@code runId} 取节点行，不靠名字 / 编码猜。</li>
 * </ul>
 *
 * <p>线程：在只读请求线程内调用；内部缓存只在单次调用内有效，不跨请求共享。
 */
@Component
public class WorkflowCardRebuilder {

    private static final Logger log = LoggerFactory.getLogger(WorkflowCardRebuilder.class);

    /** 单节点正文的展示硬顶（第一档）：与实时卡片同一量级，不额外放大 */
    private static final int NODE_TEXT_MAX = 1200;

    /** 单节点正文的下限：节点再多，也不把正文压成一句 */
    private static final int NODE_TEXT_MIN = 200;

    /** 整卡节点正文的总预算（字符）：按 AGENT 节点数均分 */
    private static final int NODE_TEXT_BUDGET = 12000;

    /** 只读运行态：按 runId 取节点行 */
    private final WorkflowRunAppService workflowRuns;

    /** 全站 ObjectMapper：解析骨架与节点产出 */
    private final ObjectMapper json;

    /**
     * @param workflowRuns 工作流只读入口（不加权限注解、无副作用）
     * @param json         全站 ObjectMapper
     */
    public WorkflowCardRebuilder(WorkflowRunAppService workflowRuns, ObjectMapper json) {
        this.workflowRuns = workflowRuns;
        this.json = json;
    }

    /**
     * 就地把一批事件里的工作流骨架补成可展示卡片。
     *
     * <p>非工作流事件、旧全量卡片、没有运行指针的骨架都原样保留。
     *
     * @param rows 事件列表（{@code events} / {@code liveEvents} 返回体）；可空
     * @return 同一个列表（元素就地修改，字段形状不变）
     */
    public List<EventResp> enrich(List<EventResp> rows) {
        if (rows == null || rows.isEmpty()) {
            return rows;
        }
        // 第一遍：只解析骨架并收集运行指针。解析失败 / 旧全量卡原样保留，不进补全集合。
        Map<Integer, ObjectNode> skeletons = new LinkedHashMap<>();
        Set<Long> runIds = new LinkedHashSet<>();
        for (int index = 0; index < rows.size(); index += 1) {
            ObjectNode body = skeletonOf(rows.get(index));
            if (body == null) {
                continue;
            }
            skeletons.put(index, body);
            runIds.add(body.path("runId").asLong(0L));
        }
        if (skeletons.isEmpty()) {
            return rows;
        }
        // 一次批量取回本次刷新涉及的全部节点行：不按卡片逐张查库
        Map<Long, List<WorkflowNodeRun>> held = workflowRuns.cardNodesOf(runIds);
        /*
         * 每条 run 只校准「最后一张」骨架（按事件 seq 判末位）。
         *
         * <p>为什么只校准最后一张：同一条运行在时间线里可能留下多张骨架（等待卡 → 续跑卡）。
         * 前端靠「前一张仍是等待态」认出同一条运行的续跑，并把旧卡收走
         * （{@code absorbWaitingWorkflowCards}）。若把历史等待卡也按库里最新状态改成已过，
         * 前端就认不出这是续跑，页面上会同时出现两张卡——反而制造新的重复。
         * 旧骨架本来就该保持它当时的样子。
         */
        Map<Long, Integer> latestIndexByRun = new LinkedHashMap<>();
        for (Map.Entry<Integer, ObjectNode> entry : skeletons.entrySet()) {
            long runId = entry.getValue().path("runId").asLong(0L);
            int index = entry.getKey();
            Integer previous = latestIndexByRun.get(runId);
            if (previous == null || seqOf(rows.get(index)) >= seqOf(rows.get(previous))) {
                latestIndexByRun.put(runId, index);
            }
        }
        // 第二遍：按 runId 补正文与回复并回写
        for (Map.Entry<Integer, ObjectNode> entry : skeletons.entrySet()) {
            ObjectNode body = entry.getValue();
            long runId = body.path("runId").asLong(0L);
            List<WorkflowNodeRun> nodeRows = held.get(runId);
            if (nodeRows == null || nodeRows.isEmpty()) {
                continue;
            }
            EventResp item = rows.get(entry.getKey());
            // 只有该 run 的最后一张骨架才按库里最新状态校准等待态；更早的卡片保持原样
            boolean calibrate = Integer.valueOf(entry.getKey()).equals(latestIndexByRun.get(runId));
            try {
                fill(body, nodeRows, calibrate);
                item.setContentText(json.writeValueAsString(body));
            } catch (Exception ex) {
                /*
                 * 补全是展示旁路：失败时保留骨架，前端仍能画出一张「已结束」的卡片。
                 * 绝不能因为补全失败把整条事件丢掉——那正是这次要修的症状。
                 */
                log.warn("工作流卡片回放补全失败 sessionId={} seq={} type={}",
                        item.getSessionId(), item.getSeq(), ex.getClass().getSimpleName());
            }
        }
        return rows;
    }

    /**
     * 没有会话事件时，只凭 run 与节点行现算一张与直播同形的卡片。
     *
     * <p>调试详情走这条。{@link #enrich} 仍只补「带骨架的会话事件」，行为不变。
     *
     * @param name       工作流展示名；可空
     * @param code       工作流编码；可空
     * @param run        运行实例
     * @param graphNodes 图内节点
     * @param outgoing   出边索引；可空
     * @param rows       节点行
     * @param tail       收口说明；可空
     * @return 卡片视图
     */
    public WorkflowRunView viewOf(String name, String code, WorkflowRun run,
                                  List<WorkflowNode> graphNodes,
                                  Map<String, List<WorkflowEdge>> outgoing,
                                  List<WorkflowNodeRun> rows, String tail) {
        WorkflowCardProjector projector = new WorkflowCardProjector(name, code, null, json);
        return projector.persisted(run, graphNodes, outgoing, rows, tail);
    }

    /**
     * 事件序号：同一 run 的多张骨架用它判末位。
     *
     * @param item 事件；可空
     * @return 序号；缺失按 0
     */
    private static int seqOf(EventResp item) {
        return item == null || item.getSeq() == null ? 0 : item.getSeq();
    }

    /**
     * 判断一条事件是不是「可补全的骨架」，是则返回解析后的对象。
     *
     * <p>只有同时满足以下条件才补全：
     * <ul>
     *   <li>事件类型是 {@code workflow}；</li>
     *   <li>正文是合法 JSON 对象，且带版本号 {@code v >= 2}（旧全量卡自带节点产出，不动）；</li>
     *   <li>带正的 {@code runId}（run 没建起来时没有产出可补）。</li>
     * </ul>
     *
     * @param item 事件；可空
     * @return 骨架对象；不满足条件时为 null
     */
    private ObjectNode skeletonOf(EventResp item) {
        if (item == null || !EventType.WORKFLOW.code().equalsIgnoreCase(item.getEventType())) {
            return null;
        }
        String stored = item.getContentText();
        if (stored == null || stored.isBlank()) {
            return null;
        }
        try {
            JsonNode root = json.readTree(stored);
            if (!(root instanceof ObjectNode body)) {
                return null;
            }
            if (body.path("v").asInt(0) < WorkflowRunJson.STORAGE_VERSION) {
                return null;
            }
            if (body.path("runId").asLong(0L) <= 0L) {
                return null;
            }
            return body;
        } catch (Exception ex) {
            // 连骨架都不合法（历史脏数据）：原样保留，交给前端出占位卡
            return null;
        }
    }

    /**
     * 按节点行补全一张骨架：状态校准 + 正文 + 用户回复。
     *
     * <p>只给 AGENT 节点补正文：工具 / 条件节点产出常是机器 JSON，倒进节点正文
     * 等于把技术细节当答案（与实时投影同一口径）。过程步（steps）本来就在骨架里，
     * 不再从库里补——库里没有这张表列，也不该为回放再复制一份。
     *
     * <p><b>状态校准</b>（{@code calibrate=true} 时）：骨架是「落库那一刻」的快照，
     * 事件要等整条运行收口才批量写库，所以刷新很可能拿到一份仍是
     * {@code WAITING_REPLY} 的骨架，而库里该节点早已 {@code SUCCESS}；
     * 后续节点也可能还写 {@code PENDING}，其实已经在跑。
     * 等待那一条不校准的话，前端会「答复被藏（{@code wfNodeReply} 遇 WAITING_REPLY 返回空串）、
     * 选项照画（{@code wfNodeChoice} 只认 WAITING_REPLY）」，也就是刷新后又冒出已选过的语言选项。
     * 因此这里按库里的真实状态改口，并在没有任何节点还在等时清掉卡片级的过期 {@code choice}。
     *
     * <p>反过来，库里确实还在等（{@code WAITING_REPLY}）时一律不动：选项、问句
     * 都保持骨架原样，行为与改前完全一致。
     *
     * @param body      卡片骨架（原地修改）
     * @param nodeRows  该 run 的节点运行态
     * @param calibrate 是否按库里状态校准等待态（同一 run 只有最后一张骨架传 true）
     */
    private void fill(ObjectNode body, List<WorkflowNodeRun> nodeRows, boolean calibrate) {
        JsonNode nodes = body.get("nodes");
        if (!(nodes instanceof ArrayNode list)) {
            return;
        }
        Map<String, WorkflowNodeRun> byAttempt = new HashMap<>();
        Map<String, WorkflowNodeRun> firstByCode = new HashMap<>();
        int agents = 0;
        for (WorkflowNodeRun row : nodeRows) {
            if (row == null || row.getNodeCode() == null || row.getNodeCode().isBlank()) {
                continue;
            }
            String code = row.getNodeCode().trim();
            int attempt = row.getAttempt() == null || row.getAttempt() < 1 ? 1 : row.getAttempt();
            // 同一编码的每一轮分开补，避免回放只剩最后一次或第一次的正文
            byAttempt.putIfAbsent(code + "#" + attempt, row);
            firstByCode.putIfAbsent(code, row);
            if ("AGENT".equalsIgnoreCase(row.getNodeType())) {
                agents += 1;
            }
        }
        // 正文预算按 AGENT 节点数均分：节点越多，每个节点给得越少，但不会低于下限
        int cap = agents <= 0 ? NODE_TEXT_MAX
                : Math.min(NODE_TEXT_MAX, Math.max(NODE_TEXT_MIN, NODE_TEXT_BUDGET / agents));
        // 校准后是否还有节点在等回复：只要有一条仍在等，卡片级的选项就必须留着
        boolean stillWaiting = false;
        for (JsonNode item : list) {
            if (!(item instanceof ObjectNode node)) {
                continue;
            }
            int attempt = node.path("attempt").asInt(0);
            String code = node.path("code").asText("").trim();
            WorkflowNodeRun row = attempt > 0
                    ? byAttempt.get(code + "#" + attempt)
                    : firstByCode.get(code);
            if (row == null) {
                continue;
            }
            // 状态校准：骨架停在「等待 / 未开始」、库里已经往前走，就按库里改口。
            // 不改口的话前端会「答复被藏、选项照画」，后续节点也仍显示「等待前置节点」。
            // 只要这条 run 里还有一个节点确实仍在等，卡片级选项就必须留着。
            if (calibrate) {
                boolean rowWaiting = calibrateStaleState(node, row);
                stillWaiting = stillWaiting || rowWaiting;
            }
            // 用户回复：续跑时落到 reply_text，是「我答了什么」的持久真源
            String reply = row.getReplyText() == null ? "" : row.getReplyText().trim();
            if (!reply.isEmpty()) {
                node.put("reply", reply);
            }
            if (!"AGENT".equalsIgnoreCase(node.path("type").asText(""))) {
                continue;
            }
            // 与实时卡片同一个取文口径（等待回复优先读 question）
            String text = WorkflowCardProjector.nodeText(row.getStatus(), row.getOutputJson(), json);
            if (text.isBlank()) {
                continue;
            }
            node.put("output", clip(text, cap));
        }
        if (calibrate && !stillWaiting) {
            /*
             * 没有任何节点还在等：卡片级选项已经过期，留着就是一组点不动的旧按钮。
             * 与实时投影同一口径——收口 / 继续跑的那一拍由后端显式不带 choice。
             */
            body.remove("choice");
        }
    }

    /**
     * 按库里的真实状态校准一个节点的状态与说明。
     *
     * <p>补全的骨架是「落库那一刻」的快照，事件要等整条运行收口才批量写库。
     * 刷新拿到的骨架因此常常落后于库：询问节点还写 {@code WAITING_REPLY}（用户其实已答），
     * 后续节点还写 {@code PENDING}（其实已经在跑）。这里一律按库里改口：
     * <ul>
     *   <li>状态改成库里的值（{@code SUCCESS} / {@code SKIPPED} / {@code FAILED} / {@code RUNNING} 等）；</li>
     *   <li>说明同步改成与实时投影同一份文案（{@code noteOf} 口径），
     *       否则会出现「已完成」配「等待你的回复」这种自相矛盾的卡；</li>
     *   <li>节点上的 {@code reply} 由调用方按 {@code reply_text} 另写，这里不动它。</li>
     * </ul>
     *
     * <p>库里仍是 {@code WAITING_REPLY} 时不改：那说明用户确实还没答，选项必须保留。
     *
     * @param node 骨架上的节点对象（原地修改）
     * @param row  同一节点的运行态行
     * @return true 表示库里这个节点仍在等待（调用方据此保留卡片级选项）
     */
    private static boolean calibrateStaleState(ObjectNode node, WorkflowNodeRun row) {
        String rowStatus = row.getStatus() == null ? "" : row.getStatus().trim().toUpperCase();
        boolean rowWaiting = isWaitingStatus(rowStatus);
        String cardStatus = node.path("status").asText("").trim().toUpperCase();
        if (cardStatus.isEmpty() || rowStatus.isEmpty()) {
            return rowWaiting;
        }
        // 库里已是终态 / 运行中而骨架还停在等待或未开始：这一拍必须按库里改口
        if (!rowWaiting && !cardStatus.equals(rowStatus) && isStaleStatus(cardStatus)) {
            node.put("status", rowStatus);
            // 说明句与实时投影同一份口径，避免「已完成」配「等待你的回复」
            String error = row.getErrorSummary() == null ? "" : row.getErrorSummary().trim();
            node.put("note", noteOf(rowStatus, error));
        }
        return rowWaiting;
    }

    /**
     * 骨架状态是否「已经落后于库」——只有这两种才会被库里状态盖掉。
     *
     * <p>只认「未开始」与「等待外部输入」：这两态的共同点是**还没产出结果**。
     * 库里若已 SUCCESS / FAILED / RUNNING，说明这一段其实已经发生，骨架是在落后。
     *
     * <p>不覆盖 {@code RUNNING} / {@code FAILED} 等已发生的骨架：它们是落库那一刻的真实态，
     * 保持原样才能让回放保留「当时在跑 / 当时失败」的过程。
     *
     * @param cardStatus 骨架上的状态（大写）
     * @return true 表示可以被库里状态校准
     */
    private static boolean isStaleStatus(String cardStatus) {
        return "PENDING".equals(cardStatus) || isWaitingStatus(cardStatus);
    }

    /**
     * 是否「在等外部输入」的状态（对话回复 / 人工处理）。
     *
     * @param status 状态码；可空
     * @return true 表示仍在等待
     */
    private static boolean isWaitingStatus(String status) {
        String code = status == null ? "" : status.trim().toUpperCase();
        WorkflowNodeRunStatus parsed = WorkflowNodeRunStatus.parse(code);
        return parsed != null && parsed.waiting();
    }

    /**
     * 节点状态 → 卡片说明句。必须与 {@link WorkflowCardProjector} 的取文案口径一致，
     * 否则回放会出现「状态已完成、说明还写着等待」。
     *
     * @param status 状态码（大写）
     * @param error  库里已脱敏的失败摘要；可空
     * @return 非空说明
     */
    private static String noteOf(String status, String error) {
        return switch (status) {
            case "RUNNING" -> "执行中";
            case "SUCCESS" -> "已完成";
            case "FAILED" -> error.isEmpty() ? "执行失败" : clip(error, 120);
            case "SKIPPED" -> "已跳过";
            case "WAITING_HUMAN" -> "等待人工处理";
            case "WAITING_REPLY" -> "等待你的回复";
            case "CANCELLED" -> "已取消";
            default -> "等待前置节点";
        };
    }

    /**
     * 字符硬截：保留换行与 Markdown 结构，只在末尾追省略号。
     *
     * @param text 原文
     * @param max  最大长度
     * @return 不超过 max 的文本
     */
    private static String clip(String text, int max) {
        String value = text == null ? "" : text;
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max - 1)) + "…";
    }
}
