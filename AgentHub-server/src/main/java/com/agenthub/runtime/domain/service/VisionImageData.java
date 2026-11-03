package com.agenthub.runtime.domain.service;

import java.util.Locale;

/**
 * 视觉请求里内联图片（data URI）的解析与规范化。所属域：runtime（domain service）。
 *
 * <p>为什么单独成类：上游 400 里有一整类与「图片字段的写法」有关，且都只表现为一个状态码——
 * <ul>
 *   <li>{@code image/jpg} 这种非标准别名：部分中转只认 {@code image/jpeg}，其余直接 400；</li>
 *   <li>base64 里夹带换行 / 空格：浏览器 {@code readAsDataURL} 不会产生，但用户从别处粘贴、
 *       或经过某些编辑器格式化后会带进来，严格的厂商解码即失败；</li>
 *   <li>大小写混杂的 MIME：{@code Image/PNG} 少数实现不接受。</li>
 * </ul>
 * 这些都能在出站前一次性规整，且不改变图片语义，因此集中在此，避免与「读图能力」混为一谈。
 *
 * <p>边界：本类<b>不做格式转码</b>（不把 jpeg 转 png），只做声明与编码层面的规范化；
 * 转码会改变字节数并引入图像库依赖，不属于「可观测性 + 写法纠正」的范围。
 *
 * 线程安全：无状态静态纯函数。
 */
public final class VisionImageData {

    /** 允许的图片 MIME；不在其中时按 jpeg 声明，避免把未知类型当成图片发给厂商 */
    private static final String DEFAULT_MIME = "image/jpeg";

    /** 单张图片 base64 最小长度；过短几乎必然是坏数据（1x1 PNG 也有约 70 字符） */
    private static final int MIN_BASE64_CHARS = 16;

    private VisionImageData() {
    }

    /**
     * 解析 {@code data:[mime];base64,[payload]}。
     *
     * @param dataUri 原始 data URI
     * @return 规范化结果；形态不是 data URI 或 payload 为空时返回 null（调用方按非法图片拒绝）
     */
    public static Parsed parse(String dataUri) {
        if (dataUri == null) {
            return null;
        }
        String text = dataUri.trim();
        if (!text.toLowerCase(Locale.ROOT).startsWith("data:")) {
            return null;
        }
        int marker = text.toLowerCase(Locale.ROOT).indexOf(";base64,");
        if (marker <= "data:".length()) {
            return null;
        }
        String mime = normalizeMime(text.substring("data:".length(), marker));
        String payload = compactBase64(text.substring(marker + ";base64,".length()));
        if (payload.length() < MIN_BASE64_CHARS) {
            return null;
        }
        return new Parsed(mime, payload);
    }

    /**
     * 规范化 MIME：小写、去参数（{@code ;charset=…}）、别名归一。
     *
     * @param raw 声明的 MIME，可空
     * @return 可安全出站的 MIME；无法识别时退回 jpeg
     */
    static String normalizeMime(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MIME;
        }
        String mime = raw.trim().toLowerCase(Locale.ROOT);
        // 少数写法带参数：image/jpeg;charset=utf-8，厂商只认主类型
        int semicolon = mime.indexOf(';');
        if (semicolon > 0) {
            mime = mime.substring(0, semicolon).trim();
        }
        // image/jpg 是非标准别名，标准名是 image/jpeg；直接改声明不影响字节内容
        if ("image/jpg".equals(mime)) {
            return "image/jpeg";
        }
        return switch (mime) {
            case "image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp" -> mime;
            default -> DEFAULT_MIME;
        };
    }

    /**
     * 去掉 base64 里的换行与空白。保持字符顺序，只删无意义空白，解码结果不变。
     *
     * @param payload 原始 base64 片段，可空
     * @return 紧凑 base64；空输入返回空串
     */
    static String compactBase64(String payload) {
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        StringBuilder compact = new StringBuilder(payload.length());
        for (int i = 0; i < payload.length(); i++) {
            char ch = payload.charAt(i);
            if (ch == '\n' || ch == '\r' || ch == ' ' || ch == '\t') {
                continue;
            }
            compact.append(ch);
        }
        return compact.toString();
    }

    /**
     * 规范化的结果。
     *
     * @param mime    归一后的 MIME
     * @param base64  紧凑 base64 负载（不含 {@code data:} 前缀）
     */
    public record Parsed(String mime, String base64) {

        /**
         * @return 可直接下发给厂商的 data URI
         */
        public String toDataUri() {
            return "data:" + mime + ";base64," + base64;
        }
    }
}
