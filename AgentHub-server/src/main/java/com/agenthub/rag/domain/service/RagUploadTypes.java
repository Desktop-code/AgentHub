package com.agenthub.rag.domain.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 上传格式白名单。所属域：rag。
 * <p>
 * 支持 PDF / MD / TXT / DOCX / 常见图片。OCR 与 MinerU 为可选增强：无 Key / 关闭时回退本地，不炸请求。
 */
public final class RagUploadTypes {

    /** 允许的扩展名（小写，不含点） */
    public static final List<String> ALLOWED = List.of(
            "txt", "md", "pdf", "docx", "png", "jpg", "jpeg", "gif", "webp");

    /** 图片扩展名 */
    public static final Set<String> IMAGE = Set.of("png", "jpg", "jpeg", "gif", "webp");

    /** 单文件上限 20MB：一次性读进内存解析，超过这个量级应改流式或排队 */
    public static final long MAX_BYTES = 20L * 1024L * 1024L;

    private RagUploadTypes() {
    }

    /**
     * 从文件名取扩展名。
     *
     * @param fileName 原始文件名
     * @return 小写扩展名；无扩展名返回空串
     */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * @param fileName 原始文件名
     * @return true 表示在允许范围内
     */
    public static boolean allowed(String fileName) {
        return ALLOWED.contains(extensionOf(fileName));
    }

    /**
     * @param fileName 原始文件名
     * @return true 表示图片
     */
    public static boolean isImage(String fileName) {
        return IMAGE.contains(extensionOf(fileName));
    }

    /**
     * @param ext 扩展名
     * @return MIME，未知时给 application/octet-stream
     */
    public static String mimeOf(String ext) {
        return switch (ext == null ? "" : ext.toLowerCase(Locale.ROOT)) {
            case "txt" -> "text/plain";
            case "md" -> "text/markdown";
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }

    /**
     * 展示用的允许格式说明。
     *
     * @return 形如 “txt / md / pdf / …”
     */
    public static String describe() {
        return String.join(" / ", ALLOWED);
    }
}
