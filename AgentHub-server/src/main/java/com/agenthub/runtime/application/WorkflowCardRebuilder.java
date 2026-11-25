package com.agenthub.runtime.application;

import com.agenthub.runtime.api.dto.EventResp;
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.service.WorkflowCardProjector;
import com.agenthub.runtime.domain.service.WorkflowRunJson;
import com.agenthub.workflow.application.WorkflowRunAppService;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
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
 *   <li>只补「带指针」的骨架（{@code v >= 2} 且有 {@code runId}），旧全量卡原样返回，
 *       不改变历史卡片的展示。</li>
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
        // 第二遍：按 runId 补正文与回复并回写
        for (Map.Entry<Integer, ObjectNode> entry : skeletons.entrySet()) {
            ObjectNode body = entry.getValue();
            List<WorkflowNodeRun> nodeRows = held.get(body.path("runId").asLong(0L));
            if (nodeRows == null || nodeRows.isEmpty()) {
                continue;
            }
            EventResp item = rows.get(entry.getKey());
            try {
                fill(body, nodeRows);
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
     * 按节点行补全一张骨架：正文 + 用户回复。
     *
     * <p>只给 AGENT 节点补正文：工具 / 条件节点产出常是机器 JSON，倒进节点正文
     * 等于把技术细节当答案（与实时投影同一口径）。过程步（steps）本来就在骨架里，
     * 不再从库里补——库里没有这张表列，也不该为回放再复制一份。
     *
     * @param body     卡片骨架（原地修改）
     * @param nodeRows 该 run 的节点运行态
     */
    private void fill(ObjectNode body, List<WorkflowNodeRun> nodeRows) {
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
