package com.agenthub.runtime.infra.agentscope.builtin;

import com.agenthub.common.text.UserFacingText;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 天气查询（Open-Meteo）。@Tool(name) 须与 tool_item.name 一致：queryWeather。
 */
@Component
public class WeatherQueryTool {

    private static final Logger log = LoggerFactory.getLogger(WeatherQueryTool.class);
    private static final String GEOCODE_URL = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public WeatherQueryTool(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Tool(
            name = "queryWeather",
            description = "查询指定城市的实时天气与未来几天预报：气温、体感、湿度、天气现象、风力、最高最低温。"
                    + "适用于管理员问天气、出行、是否下雨。必须传入城市名（如北京、上海、杭州）；"
                    + "days 为从含今天起共 N 个自然日（1～7，默认 3），问明天+后天用 days=3。"
                    + "禁止编造天气数据。")
    public String queryWeather(
            @ToolParam(name = "city", description = "城市名称，支持中文或英文，例如北京、上海、Hangzhou") String city,
            @ToolParam(
                    name = "days",
                    description = "预报天数：从含今天起共 N 个自然日，1～7，默认 3；问明天+后天应传 days=3",
                    required = false)
            @BuiltinParam(minimum = 1, maximum = 7, defaultValue = "3") Integer days
    ) {
        if (!StringUtils.hasText(city)) {
            return "查询天气失败：请提供城市名称。";
        }
        int forecastDays = days == null ? 3 : Math.max(1, Math.min(days, 7));
        try {
            JsonNode place = geocode(city.trim());
            if (place == null) {
                return "查询天气失败：未找到城市「" + city.trim() + "」，请换一个更具体的地名。";
            }
            double lat = place.path("latitude").asDouble();
            double lon = place.path("longitude").asDouble();
            JsonNode forecast = forecast(lat, lon, forecastDays);
            return objectMapper.writeValueAsString(toResult(place, forecast));
        } catch (Exception e) {
            // 原始异常（可能含 URL、供应商报文、堆栈）只进日志；对用户只留可行动的一句话
            log.warn("[Agent][queryWeather] city={} 查询失败 err={}", city, e.getMessage(), e);
            return UserFacingText.SERVICE_UNAVAILABLE;
        }
    }

    private JsonNode geocode(String city) {
        URI uri = UriComponentsBuilder.fromUriString(GEOCODE_URL)
                .queryParam("name", city)
                .queryParam("count", 1)
                .queryParam("language", "zh")
                .queryParam("format", "json")
                .build()
                .encode()
                .toUri();
        JsonNode root = restTemplate.getForObject(uri, JsonNode.class);
        JsonNode results = root == null ? null : root.path("results");
        if (results == null || !results.isArray() || results.isEmpty()) {
            return null;
        }
        return results.get(0);
    }

    private JsonNode forecast(double lat, double lon, int days) {
        URI uri = UriComponentsBuilder.fromUriString(FORECAST_URL)
                .queryParam("latitude", lat)
                .queryParam("longitude", lon)
                .queryParam("current", "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m")
                .queryParam("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
                .queryParam("timezone", "auto")
                .queryParam("forecast_days", days)
                .build(true)
                .toUri();
        JsonNode root = restTemplate.getForObject(uri, JsonNode.class);
        if (root == null || root.isMissingNode()) {
            throw new IllegalStateException("天气服务未返回数据");
        }
        return root;
    }

    /**
     * 组装回灌/试跑同源结果：只保留播报所需字段，去掉 lat/lon/country 等纯地理技术字段。
     */
    private Map<String, Object> toResult(JsonNode place, JsonNode forecast) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("city", text(place, "name"));
        data.put("adminArea", text(place, "admin1"));
        data.put("timezone", text(forecast, "timezone"));

        JsonNode current = forecast.path("current");
        Map<String, Object> now = new LinkedHashMap<>();
        now.put("time", text(current, "time"));
        now.put("temperatureC", current.path("temperature_2m").asDouble());
        now.put("feelsLikeC", current.path("apparent_temperature").asDouble());
        now.put("humidityPercent", current.path("relative_humidity_2m").asInt());
        now.put("weather", weatherText(current.path("weather_code").asInt(-1)));
        now.put("windSpeedKmh", current.path("wind_speed_10m").asDouble());
        data.put("current", now);

        JsonNode daily = forecast.path("daily");
        List<Map<String, Object>> days = new ArrayList<>();
        JsonNode dates = daily.path("time");
        if (dates.isArray()) {
            for (int i = 0; i < dates.size(); i++) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", dates.path(i).asText());
                row.put("weather", weatherText(daily.path("weather_code").path(i).asInt(-1)));
                row.put("maxC", daily.path("temperature_2m_max").path(i).asDouble());
                row.put("minC", daily.path("temperature_2m_min").path(i).asDouble());
                row.put("precipProbPercent", daily.path("precipitation_probability_max").path(i).asInt());
                days.add(row);
            }
        }
        data.put("forecast", days);
        data.put("source", "Open-Meteo");
        return data;
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("");
        return StringUtils.hasText(value) ? value : "";
    }

    static String weatherText(int code) {
        if (code == 0) {
            return "晴";
        }
        if (code >= 1 && code <= 3) {
            return code == 1 ? "大部晴朗" : (code == 2 ? "多云" : "阴");
        }
        if (code == 45 || code == 48) {
            return "雾";
        }
        if (code >= 51 && code <= 57) {
            return "毛毛雨";
        }
        if (code >= 61 && code <= 67) {
            return code >= 66 ? "冻雨" : "雨";
        }
        if (code >= 71 && code <= 77) {
            return "雪";
        }
        if (code >= 80 && code <= 82) {
            return "阵雨";
        }
        if (code == 85 || code == 86) {
            return "阵雪";
        }
        if (code >= 95) {
            return "雷暴";
        }
        return code < 0 ? "未知" : "天气代码 " + code;
    }
}
