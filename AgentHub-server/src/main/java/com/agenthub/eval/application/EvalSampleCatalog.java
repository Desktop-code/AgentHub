package com.agenthub.eval.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.eval.domain.model.EvalSuiteSpec;
import com.agenthub.eval.domain.service.EvalSpecs;
import com.agenthub.eval.infra.EvalProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * classpath 平台样例目录。所属域：eval。
 * <p>用途：把钉死资源 {@code classpath:eval/samples/platform-smoke-suite.json}
 * （{@code suite_key=platform.smoke.rag}）装进当前租户。
 * <p><b>禁止</b>在 Java 内硬编码 / 兜底拼造 cases；缺文件、空文件、坏 JSON 一律
 * {@link BizException}，由调用方事务回滚。
 * <p>线程：无状态（仅读 classpath 与解析），可并发调用；不在构造期读文件。
 */
@Slf4j
@Component
public class EvalSampleCatalog {

    /** 与 {@link EvalProperties.Samples#getLocation()} 默认值一致；交付/文档以此为准 */
    public static final String DEFAULT_LOCATION = "classpath:eval/samples/platform-smoke-suite.json";

    private final ResourceLoader resourceLoader;
    private final ObjectMapper json;
    private final EvalProperties properties;

    /**
     * @param resourceLoader Spring 资源加载器
     * @param json           JSON 解析器
     * @param properties     评测域配置（含样例路径）
     */
    public EvalSampleCatalog(ResourceLoader resourceLoader, ObjectMapper json, EvalProperties properties) {
        this.resourceLoader = resourceLoader;
        this.json = json;
        this.properties = properties;
    }

    /**
     * 读取并解析平台样例；失败抛中文业务错（含资源路径），绝不发明 cases。
     *
     * @return 已归一化的样例
     * @throws BizException 未配置 / 缺失 / 空文件 / 不可读 / JSON 非法
     */
    public EvalSuiteSpec requirePlatformSample() {
        String location = resolveLocation();
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "平台样例资源缺失：" + stripClasspathPrefix(location));
        }
        String text;
        try (InputStream in = resource.getInputStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.warn("平台样例读取失败 location={}", location, ex);
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "平台样例资源不可读：" + stripClasspathPrefix(location));
        }
        if (text == null || text.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "平台样例资源为空：" + stripClasspathPrefix(location));
        }
        // 与接口导入同一套 EvalSpecs.parse，避免「样例能存、接口存不进」
        return EvalSpecs.parse(text, json);
    }

    /**
     * @return 配置的资源位置；未配时回落 {@link #DEFAULT_LOCATION}
     */
    public String resolveLocation() {
        String location = properties.getSamples() == null ? null : properties.getSamples().getLocation();
        if (location == null || location.isBlank()) {
            return DEFAULT_LOCATION;
        }
        return location.trim();
    }

    private static String stripClasspathPrefix(String location) {
        if (location == null) {
            return DEFAULT_LOCATION;
        }
        String raw = location.trim();
        if (raw.regionMatches(true, 0, "classpath:", 0, "classpath:".length())) {
            return raw.substring("classpath:".length());
        }
        return raw;
    }
}
