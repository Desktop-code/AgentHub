package com.agenthub.runtime.application;

import com.agenthub.runtime.api.dto.EventResp;
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.workflow.application.WorkflowRunAppService;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 刷新回放补全时「按库里真实状态校准等待态」的用例。所属域：runtime 的 application。
 *
 * <p>钉住刀 B 修的症状：事件要等整条运行收口才批量落库，刷新很可能拿到一份仍是
 * {@code WAITING_REPLY} 的骨架，而库里该节点早已 {@code SUCCESS}。这一拍前端会
 * 「答复被藏（{@code wfNodeReply} 遇 WAITING_REPLY 返回空串）、选项照画
 * （{@code wfNodeChoice} 只认 WAITING_REPLY）」，也就是刷新后又冒出已选过的语言选项。
 *
 * <p>同时钉住不能改坏的两条：
 * <ul>
 *   <li>库里确实还在等（{@code WAITING_REPLY}）时，选项必须原样保留；</li>
 *   <li>同一条 run 的**历史**骨架不做校准，否则前端认不出续跑、会多出一张重复卡。</li>
 * </ul>
 *
 * <p>边界：只读入参、不连库、不出站；{@code WorkflowRunAppService} 用 mock 提供节点行。
 */
class WorkflowCardRebuilderCalibrateTest {

    private static final long RUN_ID = 108L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private WorkflowRunAppService workflowRuns;
    private WorkflowCardRebuilder rebuilder;

    @BeforeEach
    void setUp() {
        workflowRuns = mock(WorkflowRunAppService.class);
        rebuilder = new WorkflowCardRebuilder(workflowRuns, JSON);
    }

    /**
     * 核心场景：骨架仍写等待、库里已经答过并往下跑。
     *
     * <p>补全后必须：询问节点不再是 WAITING_REPLY、答复可见（能读到 reply）、卡片级 choice 被清掉。
     */
    @Test
    @DisplayName("骨架仍等待、库里已成功：补全后无 choice，询问节点已完成且答复可见")
    void calibratesWaitingNodeWhenRowAlreadySucceeded() {
        when(workflowRuns.cardNodesOf(any())).thenReturn(Map.of(RUN_ID, List.of(
                row("cond_lang", "CONDITION", "SUCCESS", null),
                // 关键行：库里已经答过（reply_text=Python），状态已往下走
                row("ask_language", "AGENT", "SUCCESS", "Python"),
                row("play_impl", "AGENT", "RUNNING", null))));

        EventResp event = workflowEvent(5, waitingSkeleton());
        List<EventResp> enriched = rebuilder.enrich(List.of(event));

        ObjectNode body = parse(enriched.get(0).getContentText());
        JsonNode ask = nodeOf(body, "ask_language");
        assertNotNull(ask, "询问节点应仍在卡片上（它是已发生的一步，不能整条删掉）");
        // 1) 状态按库里改口：不再是 WAITING_REPLY
        assertEquals("SUCCESS", ask.path("status").asText(), "询问节点应按库里改成已完成");
        assertEquals("已完成", ask.path("note").asText(), "说明句要跟着改，不能还写等待");
        // 2) 答复可见：前端 wfNodeReply 只在「非等待」时才显示 reply
        assertEquals("Python", ask.path("reply").asText(), "已确认的答复要能显示");
        // 3) 过期选项清掉
        assertFalse(body.has("choice"), "没有任何节点还在等时，卡片级选项必须清掉：" + body);
        // 4) 后续进度也在
        assertEquals("RUNNING", nodeOf(body, "play_impl").path("status").asText());
    }

    /**
     * 反向：库里确实还在等，补全必须一个字都不改。
     *
     * <p>这就是「未点选、仍停在选语言时刷新」那条手点，选项还在、行为与改前一致。
     */
    @Test
    @DisplayName("库里仍等待：选项与节点状态原样保留")
    void keepsChoiceWhileRowStillWaiting() {
        when(workflowRuns.cardNodesOf(any())).thenReturn(Map.of(RUN_ID, List.of(
                row("cond_lang", "CONDITION", "SUCCESS", null),
                row("ask_language", "AGENT", "WAITING_REPLY", null),
                row("play_impl", "AGENT", "PENDING", null))));

        String skeleton = waitingSkeleton();
        List<EventResp> enriched = rebuilder.enrich(List.of(workflowEvent(5, skeleton)));

        ObjectNode body = parse(enriched.get(0).getContentText());
        JsonNode ask = nodeOf(body, "ask_language");
        assertEquals("WAITING_REPLY", ask.path("status").asText(), "仍在等待时不能改状态");
        assertEquals("等待你的回复", ask.path("note").asText());
        assertTrue(body.has("choice"), "仍在等待时选项必须保留：" + body);
        assertEquals(5, body.path("choice").path("options").size(), "选项数量不变");
    }

    /**
     * 同一条 run 的历史骨架不校准。
     *
     * <p>前端靠「前一张仍是等待态」认出同一条运行的续跑并收走旧卡
     * （{@code absorbWaitingWorkflowCards}）。若把历史等待卡也改成已过，页面上会同时出现两张卡。
     */
    @Test
    @DisplayName("同一 run 的历史骨架不做校准，只有最后一张按库状态改口")
    void calibratesOnlyLatestSkeletonOfSameRun() {
        when(workflowRuns.cardNodesOf(any())).thenReturn(Map.of(RUN_ID, List.of(
                row("ask_language", "AGENT", "SUCCESS", "Python"))));

        // seq 5 是那一轮的等待卡，seq 8 是续跑后的卡片：同一条 run 108
        EventResp waitingCard = workflowEvent(5, waitingSkeleton());
        EventResp runningCard = workflowEvent(8, waitingSkeleton());
        List<EventResp> enriched = rebuilder.enrich(List.of(waitingCard, runningCard));

        ObjectNode early = parse(enriched.get(0).getContentText());
        ObjectNode latest = parse(enriched.get(1).getContentText());

        // 历史等待卡保持原样：仍是 WAITING_REPLY、仍带 choice —— 前端据此识别续跑并收走旧卡
        assertEquals("WAITING_REPLY", nodeOf(early, "ask_language").path("status").asText(),
                "历史骨架不能改口，否则前端认不出续跑");
        assertTrue(early.has("choice"), "历史等待卡要保留选项，供前端接管判断");

        // 最后一张按库里改口
        assertEquals("SUCCESS", nodeOf(latest, "ask_language").path("status").asText());
        assertFalse(latest.has("choice"), "最后一张不该再带过期选项");
    }

    /** 老的全量卡（无 v / 无 runId）原样返回，补全不介入。 */
    @Test
    @DisplayName("旧全量卡原样保留，不被校准")
    void legacyCardUntouched() {
        String legacy = "{\"name\":\"小游戏研发\",\"code\":\"mini_game_dev\",\"status\":\"WAITING_REPLY\","
                + "\"answer\":\"请选语言\",\"nodes\":[{\"code\":\"ask_language\",\"name\":\"询问实现语言\","
                + "\"type\":\"AGENT\",\"status\":\"WAITING_REPLY\",\"note\":\"等待你的回复\"}],"
                + "\"choice\":{\"question\":\"用哪种语言？\",\"options\":[{\"id\":\"python\",\"label\":\"Python\"},"
                + "{\"id\":\"java\",\"label\":\"Java\"}]}}";
        EventResp event = workflowEvent(5, legacy);

        List<EventResp> enriched = rebuilder.enrich(List.of(event));

        assertEquals(legacy, enriched.get(0).getContentText(), "旧全量卡不动");
    }

    /**
     * 落库骨架：等待态的「小游戏研发」卡。
     *
     * <p>形状与 {@code WorkflowRunJson.compact} 一致（带 {@code v=2} 与 {@code runId}），
     * 只保留用例要断言的字段。
     */
    private static String waitingSkeleton() {
        return "{\"name\":\"小游戏研发\",\"code\":\"mini_game_dev\",\"runId\":" + RUN_ID + ","
                + "\"status\":\"WAITING_REPLY\",\"answer\":\"请选一种实现语言\","
                + "\"nodes\":["
                + "{\"code\":\"cond_lang\",\"name\":\"是否已选语言\",\"type\":\"CONDITION\","
                + "\"status\":\"SUCCESS\",\"note\":\"已完成\"},"
                + "{\"code\":\"ask_language\",\"name\":\"询问实现语言\",\"type\":\"AGENT\","
                + "\"status\":\"WAITING_REPLY\",\"note\":\"等待你的回复\"},"
                + "{\"code\":\"play_impl\",\"name\":\"游戏实现\",\"type\":\"AGENT\","
                + "\"status\":\"PENDING\",\"note\":\"等待前置节点\"}],"
                + "\"choice\":{\"question\":\"用哪种语言实现？\",\"options\":["
                + "{\"id\":\"html\",\"label\":\"HTML/CSS/JavaScript 单页\"},"
                + "{\"id\":\"python\",\"label\":\"Python\"},{\"id\":\"java\",\"label\":\"Java\"},"
                + "{\"id\":\"typescript\",\"label\":\"TypeScript\"},"
                + "{\"id\":\"other\",\"label\":\"其它，我自己说\"}]},"
                + "\"v\":2}";
    }

    /** 造一条 workflow 事件；正文是给定 JSON。 */
    private static EventResp workflowEvent(int seq, String content) {
        EventResp item = new EventResp();
        item.setSessionId(696L);
        item.setSeq(seq);
        item.setEventType(EventType.WORKFLOW.code());
        item.setSummary("工作流「小游戏研发」");
        item.setContentText(content);
        return item;
    }

    /** 造一条节点运行态行。 */
    private static WorkflowNodeRun row(String code, String type, String status, String replyText) {
        WorkflowNodeRun row = new WorkflowNodeRun();
        row.setId((long) code.hashCode());
        row.setRunId(RUN_ID);
        row.setNodeId("n_" + code);
        row.setNodeCode(code);
        row.setNodeType(type);
        row.setAttempt(1);
        row.setStatus(status);
        row.setReplyText(replyText);
        if ("SUCCESS".equals(status) && "ask_language".equals(code)) {
            // 询问节点的产出里有 choice 结构，replyOf 据此把 reply 挂到这一步
            row.setOutputJson("{\"text\":\"请选一种实现语言\",\"choice\":{\"options\":["
                    + "{\"id\":\"python\",\"label\":\"Python\"}]}}");
        }
        return row;
    }

    /** 解析卡片正文。 */
    private static ObjectNode parse(String text) {
        try {
            JsonNode root = JSON.readTree(text);
            assertTrue(root instanceof ObjectNode, "补全后仍应是合法 JSON 对象");
            return (ObjectNode) root;
        } catch (Exception ex) {
            throw new AssertionError("卡片 JSON 不可解析：" + text, ex);
        }
    }

    /** 按 code 取卡片上的节点对象。 */
    private static JsonNode nodeOf(ObjectNode body, String code) {
        JsonNode nodes = body.get("nodes");
        assertTrue(nodes instanceof ArrayNode, "卡片应带节点数组");
        for (JsonNode item : (ArrayNode) nodes) {
            if (code.equals(item.path("code").asText())) {
                return item;
            }
        }
        return null;
    }
}
