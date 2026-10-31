package com.agenthub.rag.infra.parse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MinerU / Vision 可选出站客户端。所属域：rag 的 infra。
 * <p>
 * 【边界】无 Key / 关闭 → 视为未就绪，解析层走本地，不抛配置异常。
 * 【降级】出站失败由调用方决定回退本地；本类只负责「就绪判定」与「禁止真出站占位」。
 * 【重试】本阶段不重试：避免无 Key 或失败批次定时重炸。
 * 【限流】不主动批打；单文件一次解析调用。
 * <p>
 * 真 MinerU/OCR HTTP 实现后续再补；当前 {@link #parseWithMinerU} / {@link #ocrImage}
 * 在就绪时仍返回 empty，促使上层回退本地——单测与禁出站开场均安全。
 */
@Component
public class OptionalParseClients {

    private static final Logger log = LoggerFactory.getLogger(OptionalParseClients.class);

    private final RagParseProperties properties;

    /**
     * @param properties 解析配置
     */
    public OptionalParseClients(RagParseProperties properties) {
        this.properties = properties;
    }

    /**
     * MinerU 是否可用（开关开且 Key 非空且扩展名在白名单）。
     *
     * @param fileName 文件名
     * @return true 表示可以尝试（仍可能因占位实现立刻回退）
     */
    public boolean minerUReady(String fileName) {
        RagParseProperties.Mineru m = properties.getMineru();
        if (!m.isEnabled()) {
            return false;
        }
        String key = firstNonBlank(System.getenv("AGENTHUB_MINERU_API_KEY"), m.getApiKey());
        if (!StringUtils.hasText(key)) {
            log.info("[Parse] 跳过 MinerU：无 API Key，走本地 file={}", fileName);
            return false;
        }
        if (!supportsMinerUType(fileName)) {
            return false;
        }
        return true;
    }

    /**
     * Vision OCR 是否可用。
     *
     * @return true 表示开关开且有 Key
     */
    public boolean visionReady() {
        RagParseProperties.Vision v = properties.getVision();
        if (!v.isEnabled()) {
            return false;
        }
        String key = firstNonBlank(System.getenv("AGENTHUB_VISION_API_KEY"), v.getApiKey());
        return StringUtils.hasText(key);
    }

    /**
     * 尝试 MinerU。当前为禁出站占位：始终返回 empty，由调用方回退本地。
     *
     * @param fileName 文件名
     * @param bytes    内容
     * @return 正文；empty 表示应回退
     */
    public java.util.Optional<String> parseWithMinerU(String fileName, byte[] bytes) {
        if (!minerUReady(fileName)) {
            return java.util.Optional.empty();
        }
        // 禁真出站：不发起 HTTP；记录后让上层走本地
        log.warn("[Parse] MinerU 已配置但本阶段禁出站，回退本地 file={}", fileName);
        return java.util.Optional.empty();
    }

    /**
     * 尝试图片/扫描 OCR。当前禁出站占位，返回 empty。
     *
     * @param bytes 图片字节
     * @param mime  MIME
     * @return OCR 正文；empty 表示不可用
     */
    public java.util.Optional<String> ocrImage(byte[] bytes, String mime) {
        if (!visionReady()) {
            return java.util.Optional.empty();
        }
        log.warn("[Parse] Vision OCR 已配置但本阶段禁出站，跳过 OCR mime={}", mime);
        return java.util.Optional.empty();
    }

    private boolean supportsMinerUType(String fileName) {
        String ext = "";
        if (fileName != null) {
            int dot = fileName.lastIndexOf('.');
            if (dot >= 0 && dot < fileName.length() - 1) {
                ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
            }
        }
        Set<String> types = Arrays.stream(properties.getMineru().getTypes().split(","))
                .map(String::trim)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        return types.contains(ext);
    }

    private static String firstNonBlank(String a, String b) {
        if (StringUtils.hasText(a)) {
            return a.trim();
        }
        if (StringUtils.hasText(b)) {
            return b.trim();
        }
        return "";
    }
}
