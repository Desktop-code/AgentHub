package com.agenthub.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.rolling.TimeBasedFileNamingAndTriggeringPolicy;
import ch.qos.logback.core.util.FileSize;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 职责：校验按天滚动 Logback 配置声明，并用人工时钟跨天证明会切出带日期的归档。
 * 所属域：common（运维日志，无业务逻辑）
 * 关键约束：不启动 Spring 全容器；fileNamePattern 用相对路径，避免 Windows 盘符冒号干扰 %d。
 */
class LogbackDailyRollingTest {

    /**
     * 用途：确认 classpath 上的 logback-spring.xml 声明了按天 pattern 与默认 30 天保留。
     */
    @Test
    void logbackSpringXml_declaresDailyPatternAndMaxHistoryDefault() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("logback-spring.xml")) {
            assertNotNull(in, "缺少 logback-spring.xml");
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
            XPath xp = XPathFactory.newInstance().newXPath();

            String pattern = xp.evaluate(
                    "//rollingPolicy/fileNamePattern/text()", doc).trim();
            assertTrue(pattern.contains("%d{yyyy-MM-dd}"),
                    "归档 pattern 须含按天日期: " + pattern);
            assertTrue(pattern.contains("%i"),
                    "SizeAndTimeBased 须含 %i: " + pattern);

            String maxHistoryDefault = xp.evaluate(
                    "//springProperty[@name='LOG_MAX_HISTORY']/@defaultValue", doc).trim();
            assertEquals("30", maxHistoryDefault, "maxHistory 默认应为 30 天");

            NodeList policies = (NodeList) xp.evaluate(
                    "//rollingPolicy[@class='ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy']",
                    doc, XPathConstants.NODESET);
            assertTrue(policies.getLength() >= 1, "须使用 SizeAndTimeBasedRollingPolicy");
        }
    }

    /**
     * 用途：写入当日日志后把触发策略时钟推过午夜，再 append 一次，证明滚出含 yyyy-MM-dd 的归档。
     */
    @Test
    void sizeAndTimeBasedPolicy_rollsToDatedArchive() throws Exception {
        // 相对 user.dir，避免盘符冒号；目录落在 target 下随构建清理
        Path dir = Path.of("target", "agenthub-log-roll-" + System.nanoTime());
        Files.createDirectories(dir);
        String rel = dir.toString().replace('\\', '/');
        Path current = dir.resolve("agenthub.log");
        String archivePattern = rel + "/agenthub.%d{yyyy-MM-dd}.%i.log";

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        String loggerName = "roll-test-" + System.nanoTime();
        Logger logger = context.getLogger(loggerName);
        logger.detachAndStopAllAppenders();
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);

        RollingFileAppender<ILoggingEvent> appender = new RollingFileAppender<>();
        appender.setContext(context);
        appender.setName(loggerName);
        appender.setFile(rel + "/agenthub.log");

        SizeAndTimeBasedRollingPolicy<ILoggingEvent> policy = new SizeAndTimeBasedRollingPolicy<>();
        policy.setContext(context);
        policy.setParent(appender);
        policy.setFileNamePattern(archivePattern);
        policy.setMaxFileSize(FileSize.valueOf("100MB"));
        policy.setMaxHistory(30);
        policy.setTotalSizeCap(FileSize.valueOf("1GB"));
        appender.setRollingPolicy(policy);

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%msg%n");
        encoder.start();
        appender.setEncoder(encoder);

        policy.start();
        appender.start();
        assertTrue(policy.isStarted(), "RollingPolicy 未启动");
        logger.addAppender(appender);

        TimeBasedFileNamingAndTriggeringPolicy<ILoggingEvent> triggering =
                policy.getTimeBasedFileNamingAndTriggeringPolicy();
        assertNotNull(triggering, "须有按天触发策略");
        long day0 = triggering.getCurrentTime();
        triggering.setCurrentTime(day0);

        appender.doAppend(new LoggingEvent(
                Logger.class.getName(), logger, Level.INFO, "day0-line", null, null));
        assertTrue(Files.exists(current) && Files.size(current) > 0,
                "跨天前提当前日志应有内容: " + current);

        // 人工跨过午夜：下一次 append 走时间触发并滚出带日期归档
        triggering.setCurrentTime(day0 + TimeUnit.DAYS.toMillis(1) + TimeUnit.HOURS.toMillis(1));
        appender.doAppend(new LoggingEvent(
                Logger.class.getName(), logger, Level.INFO, "day1-line", null, null));

        appender.stop();
        logger.detachAndStopAllAppenders();

        List<String> datedArchives = listNames(dir).stream()
                .filter(name -> name.matches("agenthub\\.\\d{4}-\\d{2}-\\d{2}\\.\\d+\\.log"))
                .toList();
        assertTrue(!datedArchives.isEmpty(),
                "应产生含 yyyy-MM-dd 的归档，目录内容=" + listNames(dir));
    }

    /**
     * 用途：收集目录下文件名，供断言失败时排查。
     */
    private static List<String> listNames(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.map(p -> p.getFileName().toString()).toList();
        }
    }
}
