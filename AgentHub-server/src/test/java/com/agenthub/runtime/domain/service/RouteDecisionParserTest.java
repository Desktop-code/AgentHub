package com.agenthub.runtime.domain.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判路由 JSON 契约与兼容旧单 key。
 */
class RouteDecisionParserTest {

    @Test
    void parsesOrderedArrayAndDedupes() {
        String reply = """
                [
                  {"route_key":"weather","sub_query":"查北京天气"},
                  {"route_key":"user","sub_query":"查用户资料"},
                  {"route_key":"weather","sub_query":"重复应丢"}
                ]
                """;
        List<RouteDecisionParser.Item> items = RouteDecisionParser.parse(
                reply, List.of("weather", "user", "order"), 3, "整句");
        assertEquals(2, items.size());
        assertEquals("weather", items.get(0).routeKey());
        assertEquals("查北京天气", items.get(0).subQuery());
        assertEquals("user", items.get(1).routeKey());
    }

    @Test
    void dropsInvalidKeysAndCaps() {
        String reply = "[{\"route_key\":\"ghost\",\"sub_query\":\"x\"},{\"route_key\":\"user\",\"sub_query\":\"u\"},"
                + "{\"route_key\":\"weather\",\"sub_query\":\"w\"},{\"route_key\":\"order\",\"sub_query\":\"o\"}]";
        List<RouteDecisionParser.Item> items = RouteDecisionParser.parse(
                reply, List.of("weather", "user", "order"), 2, "整句");
        assertEquals(2, items.size());
        assertEquals("user", items.get(0).routeKey());
        assertEquals("weather", items.get(1).routeKey());
    }

    @Test
    void legacySingleKeyStillWorks() {
        List<RouteDecisionParser.Item> items = RouteDecisionParser.parse(
                "选 weather 即可", List.of("weather", "user"), 3, "今天天气怎么样");
        assertEquals(1, items.size());
        assertEquals("weather", items.get(0).routeKey());
        assertEquals("今天天气怎么样", items.get(0).subQuery());
    }

    @Test
    void emptyWhenAllInvalid() {
        assertTrue(RouteDecisionParser.parse(
                "[{\"route_key\":\"nope\",\"sub_query\":\"x\"}]", List.of("weather"), 3, "q").isEmpty());
    }

    @Test
    void toDecisionJsonRoundTripShape() {
        String json = RouteDecisionParser.toDecisionJson(List.of(
                new RouteDecisionParser.Item("a", "问A"),
                new RouteDecisionParser.Item("b", "问B")));
        assertTrue(json.contains("\"route_key\":\"a\""));
        assertTrue(json.contains("\"sub_query\":\"问B\""));
    }

    /** 判路由产出的依据必须原样保留：工作台智能网关步骤只展示这句话，不再回退到内部标签。 */
    @Test
    void keepsNaturalLanguageReasonFromJudge() {
        String reply = """
                [{"route_key":"order","sub_query":"查订单 A123",
                  "reason":"该专家负责订单与物流查询，用户问的是订单进度"}]
                """;
        List<RouteDecisionParser.Item> items = RouteDecisionParser.parse(
                reply, List.of("order", "weather"), 3, "整句");
        assertEquals(1, items.size());
        assertEquals("该专家负责订单与物流查询，用户问的是订单进度", items.get(0).reason());
    }

    /** 模型漏写 reason 时不编造：留空，由前端降级成中性句。 */
    @Test
    void missingReasonStaysEmpty() {
        List<RouteDecisionParser.Item> items = RouteDecisionParser.parse(
                "[{\"route_key\":\"order\",\"sub_query\":\"查订单\"}]",
                List.of("order"), 3, "整句");
        assertEquals("", items.get(0).reason());
    }

    /** 旧模板 / 旧数据回流「父模型」这类系统标签：整条丢弃，不能当成判定依据展示。 */
    @Test
    void dropsInternalReasonLabels() {
        List<RouteDecisionParser.Item> items = RouteDecisionParser.parse(
                "[{\"route_key\":\"order\",\"sub_query\":\"查订单\",\"reason\":\"父模型\"}]",
                List.of("order"), 3, "整句");
        assertEquals(1, items.size());
        assertEquals("", items.get(0).reason());
    }

    /** 兼容模型常见的理由字段别名与多行表达：压成一行，避免把分点推理塞进时间线。 */
    @Test
    void acceptsReasonAliasesAndFlattensWhitespace() {
        List<RouteDecisionParser.Item> items = RouteDecisionParser.parse(
                "[{\"route_key\":\"order\",\"sub_query\":\"查订单\",\"basis\":\"负责订单查询\\n且用户问的是物流\"}]",
                List.of("order"), 3, "整句");
        assertEquals("负责订单查询 且用户问的是物流", items.get(0).reason());
    }

    /** 决策 JSON 里带上理由，便于运行页 / 观测直接读，不需要再打一轮模型。 */
    @Test
    void decisionJsonKeepsReason() {
        String json = RouteDecisionParser.toDecisionJson(List.of(
                new RouteDecisionParser.Item("a", "问A", "候选人负责A类请求")));
        assertTrue(json.contains("\"reason\":\"候选人负责A类请求\""), json);
    }
}
