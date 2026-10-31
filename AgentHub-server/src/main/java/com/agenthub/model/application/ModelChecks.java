package com.agenthub.model.application;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;

/**
 * 模型入参的业务校验。所属域：model。注解只挡住空值，协议、地址和 JSON 在这里收口。
 */
public final class ModelChecks {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ModelChecks() {
    }

    /**
     * 缺省视为正常。只接受基座的 1 / 0。
     *
     * @param raw 请求里的状态，可空
     * @return 入库值
     */
    public static int status(Integer raw) {
        if (raw == null) {
            return RecordStatus.ENABLED.getCode();
        }
        if (raw != RecordStatus.ENABLED.getCode() && raw != RecordStatus.DISABLED.getCode()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "状态只能是 1 正常或 0 停用");
        }
        return raw;
    }

    /**
     * @param raw 协议名
     * @return 枚举
     * @throws BizException 不是已登记的协议时
     */
    public static ModelProtocol protocol(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "协议不能为空");
        }
        try {
            return ModelProtocol.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的协议");
        }
    }

    /**
     * 用途入参：旧码映射为新码后返回规范枚举，调用方以 {@link ModelCapability#name()} 落库。
     *
     * @param raw 用途名（新码或 LLM/INTENT/EMBED/OCR/PDF）
     * @return 规范枚举
     * @throws BizException 空白或未知码
     */
    public static ModelCapability capability(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "用途不能为空");
        }
        try {
            return ModelCapability.parse(raw);
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的用途");
        }
    }

    /**
     * 只接受 http 和 https，避免探活被带到 file 或其他协议。
     *
     * @param raw 供应商地址
     * @return 去掉首尾空白后的地址
     */
    public static String baseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "baseUrl 不能为空");
        }
        String url = raw.trim();
        if (url.length() > 512) {
            throw new BizException(ErrorCode.BAD_REQUEST, "baseUrl 最长 512");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "baseUrl 不合法");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) || uri.getHost() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "baseUrl 必须以 http 或 https 开头");
        }
        return url;
    }

    /**
     * @param raw 厂商 Key
     * @return 去掉首尾空白的明文，随后马上加密，不要留在日志里
     */
    public static String apiKey(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "apiKey 不能为空");
        }
        String key = raw.trim();
        if (key.length() > 512) {
            throw new BizException(ErrorCode.BAD_REQUEST, "apiKey 最长 512");
        }
        return key;
    }

    /**
     * @param raw  原文
     * @param max  最大长度
     * @param label 字段中文名，用于错误提示
     * @return 去掉首尾空白的文本
     */
    public static String requiredText(String raw, int max, String label) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "不能为空");
        }
        String text = raw.trim();
        if (text.length() > max) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "最长 " + max);
        }
        return text;
    }

    /**
     * @param raw 备注，可空
     * @return 空白变成 null
     */
    public static String remark(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.length() > 500) {
            throw new BizException(ErrorCode.BAD_REQUEST, "备注最长 500");
        }
        return text;
    }

    /**
     * 空串存 null。非空时必须是合法 JSON，避免库里留下半截文本。
     *
     * @param raw 扩展 JSON
     * @return 可入库的文本
     */
    public static String extraJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.length() > 2000) {
            throw new BizException(ErrorCode.BAD_REQUEST, "extraJson 最长 2000");
        }
        try {
            JSON.readTree(text);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "extraJson 不是合法 JSON");
        }
        return text;
    }
}
