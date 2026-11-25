package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.MemoryFact;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具必填参数闸门：缺必填追问、记忆可补参、用户给了就放行、臆造被拦、可选参数不拦。
 * 所属域：runtime 的纯逻辑单测，不连库、不调模型、不出站。
 */
class RequiredToolArgsTest {

    /** 与 FactKeyNormalizer 内置别名一致的最小映射，避免单测依赖 memory 实现 */
    private static final java.util.function.Function<String, Optional<String>> CANONICAL = raw -> {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String key = raw.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (key) {
            case "city", "residence", "residence_city", "居住城市", "所在城市" -> Optional.of("residence_city");
            case "timezone", "时区" -> Optional.of("timezone");
            default -> Optional.of(key);
        };
    };

    private static final List<String> CITY_REQUIRED = List.of("city");

    /**
     * 验收 1：无记忆、用户没给城市 → 不执行工具，只返回追问文案；不得出现任何猜测值。
     */
    @Test
    void noMemoryAndNoCityAsksUserInsteadOfExecuting() {
        RequiredToolArgs.Result result = RequiredToolArgs.check(
                "queryWeather", CITY_REQUIRED, Map.of(),
                RequiredToolArgs.Evidence.empty(), RequiredToolArgs.Rules.defaults(), CANONICAL);

        assertFalse(result.allowed(), "无依据时不得放行");
        assertEquals(List.of("city"), result.missing());
        assertTrue(result.input().isEmpty(), "不得自行填入任何城市");
        assertFalse(result.message().isBlank());
        assertTrue(result.message().contains("未执行"));
        assertTrue(result.message().contains("city"));
        assertTrue(result.message().contains("追问"));
    }

    /**
     * 验收 2：已注入居住城市 LONG → 可直接补 city 并放行，且入参就是记忆里的值。
     */
    @Test
    void injectedResidenceMemoryFillsCity() {
        RequiredToolArgs.Evidence evidence = new RequiredToolArgs.Evidence("今天天气如何？", "今天天气如何？",
                List.of(new MemoryFact("residence_city", "居住城市", "杭州")));

        RequiredToolArgs.Result result = RequiredToolArgs.check(
                "queryWeather", CITY_REQUIRED, Map.of(), evidence,
                RequiredToolArgs.Rules.defaults(), CANONICAL);

        assertTrue(result.allowed(), "记忆可唯一补参时应放行");
        assertEquals("杭州", result.input().get("city"));
        assertEquals(List.of("city"), result.filled());
        assertTrue(result.missing().isEmpty());
        assertTrue(result.message().isEmpty());
    }

    /**
     * 记忆写成「居住城市：杭州市」也要能取到值，并归一成城市名。
     */
    @Test
    void labeledMemoryValueIsExtracted() {
        RequiredToolArgs.Evidence evidence = new RequiredToolArgs.Evidence("天气", "天气",
                List.of(new MemoryFact("residence_city", "居住城市", "居住城市：杭州市")));

        RequiredToolArgs.Result result = RequiredToolArgs.check(
                "queryWeather", CITY_REQUIRED, Map.of(), evidence,
                RequiredToolArgs.Rules.defaults(), CANONICAL);

        assertTrue(result.allowed());
        assertEquals("杭州市", result.input().get("city"));
    }

    /**
     * 验收 3：用户口头给了城市 → 直接放行，原入参不变，不做任何改写。
     */
    @Test
    void userProvidedCityPassesThroughUnchanged() {
        RequiredToolArgs.Evidence evidence = new RequiredToolArgs.Evidence(
                "【当前用户】\n上海今天天气如何？", "【当前用户】\n上海今天天气如何？", List.of());

        RequiredToolArgs.Result result = RequiredToolArgs.check(
                "queryWeather", CITY_REQUIRED, Map.of("city", "上海"), evidence,
                RequiredToolArgs.Rules.defaults(), CANONICAL);

        assertTrue(result.allowed());
        assertEquals("上海", result.input().get("city"));
        assertTrue(result.filled().isEmpty(), "用户已给出时不得再改写成记忆值");
    }

    /**
     * 验收 4：模型自带臆造城市（永平）且三档来源都没有 → 拦下，假参不出站。
     */
    @Test
    void fabricatedCityIsBlockedBeforeOutbound() {
        RequiredToolArgs.Evidence evidence = new RequiredToolArgs.Evidence(
                "【当前用户】\n今天天气如何？", "【当前用户】\n今天天气如何？", List.of());

        RequiredToolArgs.Result result = RequiredToolArgs.check(
                "queryWeather", CITY_REQUIRED, Map.of("city", "永平"), evidence,
                RequiredToolArgs.Rules.defaults(), CANONICAL);

        assertFalse(result.allowed(), "无依据的城市必须拦下");
        assertEquals(List.of("city"), result.unverified());
        assertTrue(result.message().contains("找不到依据"));
    }

    /**
     * 用户说了「北京市」，模型填「北京」：同一城市，去行政后缀后应视为有依据。
     */
    @Test
    void citySuffixDifferenceStillCountsAsGrounded() {
        RequiredToolArgs.Evidence evidence = new RequiredToolArgs.Evidence(
                "北京市明天要下雨吗", "北京市明天要下雨吗", List.of());

        RequiredToolArgs.Result result = RequiredToolArgs.check(
                "queryWeather", CITY_REQUIRED, Map.of("city", "北京"), evidence,
                RequiredToolArgs.Rules.defaults(), CANONICAL);

        assertTrue(result.allowed());
    }

    /**
     * 有两条不同城市的居住记忆 → 映射不唯一，按模糊处理，改为追问而不是任选一条。
     */
    @Test
    void multipleMemoryValuesAreTreatedAsAmbiguous() {
        RequiredToolArgs.Evidence evidence = new RequiredToolArgs.Evidence("天气", "天气",
                List.of(new MemoryFact("residence_city", "居住城市", "杭州"),
                        new MemoryFact("residence_city", "老家", "温州")));

        RequiredToolArgs.Result result = RequiredToolArgs.check(
                "queryWeather", CITY_REQUIRED, Map.of(), evidence,
                RequiredToolArgs.Rules.defaults(), CANONICAL);

        assertFalse(result.allowed());
        assertEquals(List.of("city"), result.missing());
        assertTrue(result.input().isEmpty());
    }

    /**
     * 抽取模型常写「我常住杭州」：剥掉人称与引导语后仍可唯一取值，应按记忆补参。
     */
    @Test
    void narrativeMemoryLeadInIsStrippedToBareValue() {
        RequiredToolArgs.Evidence evidence = new RequiredToolArgs.Evidence("天气", "天气",
                List.of(new MemoryFact("residence_city", "居住地", "我常住杭州")));

        RequiredToolArgs.Result result = RequiredToolArgs.check(
                "queryWeather", CITY_REQUIRED, Map.of(), evidence,
                RequiredToolArgs.Rules.defaults(), CANONICAL);

        assertTrue(result.allowed());
        assertEquals("杭州", result.input().get("city"), "只应把城市名写进出站参数");
    }

    /**
     * 记忆里同时提到多个城市时无法确定唯一值，必须追问而不是任选。
     */
    @Test
    void multiCityNarrativeMemoryIsRejected() {
        RequiredToolArgs.Evidence evidence = new RequiredToolArgs.Evidence("天气", "天气",
                List.of(new MemoryFact("residence_city", "居住地", "有时在杭州，有时在上海")));

        RequiredToolArgs.Result result = RequiredToolArgs.check(
                "queryWeather", CITY_REQUIRED, Map.of(), evidence,
                RequiredToolArgs.Rules.defaults(), CANONICAL);

        assertFalse(result.allowed());
        assertEquals(List.of("city"), result.missing());
    }

    /**
     * 没有 fact_key 的记忆无法证明对应哪个字段，不得拿来补参。
     */
    @Test
    void memoryWithoutFactKeyCannotFillField() {
        RequiredToolArgs.Evidence evidence = new RequiredToolArgs.Evidence("天气", "天气",
                List.of(new MemoryFact(null, "居住城市", "杭州")));

        RequiredToolArgs.Result result = RequiredToolArgs.check(
                "queryWeather", CITY_REQUIRED, Map.of(), evidence,
                RequiredToolArgs.Rules.defaults(), CANONICAL);

        assertFalse(result.allowed());
        assertEquals(List.of("city"), result.missing());
    }

    /**
     * 可选参数（days）缺省不拦；无 required 的工具直接放行。
     */
    @Test
    void optionalParamsAndEmptyRequiredAreNotBlocked() {
        RequiredToolArgs.Result noRequired = RequiredToolArgs.check(
                "systemInfo", List.of(), Map.of(),
                RequiredToolArgs.Evidence.empty(), RequiredToolArgs.Rules.defaults(), CANONICAL);
        assertTrue(noRequired.allowed());

        RequiredToolArgs.Result onlyCity = RequiredToolArgs.check(
                "queryWeather", CITY_REQUIRED, Map.of("city", "北京", "days", 1),
                new RequiredToolArgs.Evidence("北京天气", "北京天气", List.of()),
                RequiredToolArgs.Rules.defaults(), CANONICAL);
        assertTrue(onlyCity.allowed());

        RequiredToolArgs.Result optionalMissing = RequiredToolArgs.check(
                "queryWeather", CITY_REQUIRED, Map.of("city", "北京"),
                new RequiredToolArgs.Evidence("北京天气", "北京天气", List.of()),
                RequiredToolArgs.Rules.defaults(), CANONICAL);
        assertTrue(optionalMissing.allowed(), "days 不是 required，不得拦");
    }

    /**
     * 空白字符串等同缺失，必须追问；不能把 "" 当成合法城市出站。
     */
    @Test
    void blankValueCountsAsMissing() {
        RequiredToolArgs.Result result = RequiredToolArgs.check(
                "queryWeather", CITY_REQUIRED, Map.of("city", "   "),
                RequiredToolArgs.Evidence.empty(), RequiredToolArgs.Rules.defaults(), CANONICAL);

        assertFalse(result.allowed());
        assertEquals(List.of("city"), result.missing());
    }

    /**
     * 通用策略：其它工具的实体必填参数同样按此规则拦（例如 url 之外的 location）。
     */
    @Test
    void otherToolsFollowSameRule() {
        RequiredToolArgs.Result blocked = RequiredToolArgs.check(
                "queryAirQuality", List.of("location", "date"), Map.of("location", "某市"),
                RequiredToolArgs.Evidence.empty(), RequiredToolArgs.Rules.defaults(), CANONICAL);
        assertFalse(blocked.allowed());
        assertTrue(blocked.unverified().contains("location"), "无依据的实体值按编造拦");
        assertTrue(blocked.missing().contains("date"), "缺失的非实体必填也应追问");
    }

    /**
     * required 解析兼容 schema 里 required 为数组或缺失/类型不符。
     */
    @Test
    void requiredOfToleratesMissingAndWrongTypes() {
        assertTrue(RequiredToolArgs.requiredOf(null).isEmpty());
        assertTrue(RequiredToolArgs.requiredOf(Map.of()).isEmpty());
        assertTrue(RequiredToolArgs.requiredOf(Map.of("required", "city")).isEmpty());
        assertEquals(List.of("city"), RequiredToolArgs.requiredOf(Map.of("required", List.of("city"))));
    }
}
