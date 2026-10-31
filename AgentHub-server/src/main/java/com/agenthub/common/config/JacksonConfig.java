package com.agenthub.common.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/**
 * JSON 时间格式。所属域：common。
 * 写出带毫秒（yyyy-MM-dd HH:mm:ss.SSS），读入兼容无小数秒的旧串，避免秒级撞车误判并行。
 */
@Configuration
public class JacksonConfig {

    /** 对外序列化固定带毫秒 */
    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS";

    /**
     * 覆盖 Java 8 时间模块的默认 ISO 格式，和国内控制台展示一致。
     *
     * @return Jackson 定制器
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer agentHubDateTime() {
        DateTimeFormatter writer = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
        // 入参兼容历史「无毫秒」与带 1～9 位小数
        DateTimeFormatter reader = new DateTimeFormatterBuilder()
                .appendPattern("yyyy-MM-dd HH:mm:ss")
                .optionalStart()
                .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
                .optionalEnd()
                .toFormatter();
        return (Jackson2ObjectMapperBuilder builder) -> builder
                .serializers(new LocalDateTimeSerializer(writer))
                .deserializers(new LocalDateTimeDeserializer(reader));
    }
}
