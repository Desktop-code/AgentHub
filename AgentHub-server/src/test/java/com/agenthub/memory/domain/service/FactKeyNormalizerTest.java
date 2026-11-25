package com.agenthub.memory.domain.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * fact_key 同义归一与格式校验。
 */
class FactKeyNormalizerTest {

    private final FactKeyNormalizer normalizer = new FactKeyNormalizer(Map.of());

    @Test
    void residenceSynonymsCollapse() {
        assertEquals("residence_city", normalizer.normalize("residence").orElseThrow());
        assertEquals("residence_city", normalizer.normalize("city_of_residence").orElseThrow());
        assertEquals("residence_city", normalizer.normalize("Living-City").orElseThrow());
        assertEquals("residence_city", normalizer.normalize("居住城市").orElseThrow());
        assertEquals("residence_city", normalizer.normalize("常驻城市").orElseThrow());
    }

    @Test
    void yamlAliasOverrides() {
        FactKeyNormalizer custom = new FactKeyNormalizer(Map.of("home_town", "residence_city", "city", "work_city"));
        assertEquals("residence_city", custom.normalize("home_town").orElseThrow());
        // 配置覆盖内置 city→residence_city
        assertEquals("work_city", custom.normalize("city").orElseThrow());
    }

    @Test
    void blankOrIllegalRejected() {
        assertTrue(normalizer.normalize(null).isEmpty());
        assertTrue(normalizer.normalize("  ").isEmpty());
        assertTrue(normalizer.normalize("Bad Key!!").isEmpty());
        assertTrue(normalizer.normalize("a".repeat(65)).isEmpty());
    }

    /** 饮食偏好族：换一种说法必须归到同一 canonical，否则写库会变成两条 */
    @Test
    void foodPreferenceFamilyCollapses() {
        assertEquals(FactKeyNormalizer.FOOD_PREFERENCE, normalizer.normalize("food_preference").orElseThrow());
        assertEquals(FactKeyNormalizer.FOOD_PREFERENCE, normalizer.normalize("favorite_food").orElseThrow());
        assertEquals(FactKeyNormalizer.FOOD_PREFERENCE, normalizer.normalize("Favourite-Food").orElseThrow());
        assertEquals(FactKeyNormalizer.FOOD_PREFERENCE, normalizer.normalize("饮食偏好").orElseThrow());
        assertEquals(FactKeyNormalizer.FOOD_PREFERENCE, normalizer.normalize("喜爱食物").orElseThrow());
        assertEquals(FactKeyNormalizer.FOOD_PREFERENCE, normalizer.normalize("爱吃的").orElseThrow());
    }

    /** 饮品偏好族同理，且不能并进饮食族 */
    @Test
    void beveragePreferenceFamilyCollapsesSeparately() {
        assertEquals(FactKeyNormalizer.BEVERAGE_PREFERENCE, normalizer.normalize("favorite_drink").orElseThrow());
        assertEquals(FactKeyNormalizer.BEVERAGE_PREFERENCE, normalizer.normalize("beverage_preference").orElseThrow());
        assertEquals(FactKeyNormalizer.BEVERAGE_PREFERENCE, normalizer.normalize("喜爱饮品").orElseThrow());
        assertEquals(FactKeyNormalizer.BEVERAGE_PREFERENCE, normalizer.normalize("爱喝的").orElseThrow());
    }

    /** 饮食禁忌是限制不是偏好：不能被偏好族吃掉 */
    @Test
    void dietaryRestrictionStaysOutOfPreferenceFamily() {
        assertEquals("dietary_restriction", normalizer.normalize("饮食禁忌").orElseThrow());
        assertEquals("dietary_restriction", normalizer.normalize("food_taboo").orElseThrow());
    }

    /** 同族键集合包含自身与所有 ascii 别名，用于历史行并轨查询 */
    @Test
    void familyKeysCoverCanonicalAndAsciiAliases() {
        var keys = normalizer.familyKeysOf(FactKeyNormalizer.FOOD_PREFERENCE);
        assertTrue(keys.contains(FactKeyNormalizer.FOOD_PREFERENCE));
        assertTrue(keys.contains("favorite_food"));
        // 中文别名不是合法 fact_key，不会以原样落库，因此不进 IN 查询
        assertTrue(!keys.contains("饮食偏好"));
        // 未登记的裸 key 退化为只查自己，行为不弱于现状
        assertEquals(java.util.Set.of("health_status"), normalizer.familyKeysOf("health_status"));
    }
}
