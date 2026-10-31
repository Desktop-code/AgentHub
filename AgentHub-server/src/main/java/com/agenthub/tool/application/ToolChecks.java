package com.agenthub.tool.application;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.tool.domain.model.ToolSourceType;
import com.agenthub.tool.domain.model.ToolTransport;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;

/**
 * 工具入参的业务校验。所属域：tool。注解只挡住空值，地址、枚举和 JSON 在这里收口。
 */
public final class ToolChecks {

    /** 密文列最长 1024，中文鉴权头按这个字符数才放得下 AES-GCM 结果 */
    public static final int HEADER_MAX = 180;

    private static final int SCHEMA_MAX = 60000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolChecks() {
    }

    /**
     * 新建源只接受 MCP。空值按 MCP 处理，BUILTIN 留给种子。
     *
     * @param raw 请求里的类型，可空
     * @return MCP
     */
    public static ToolSourceType creatableType(String raw) {
        if (raw == null || raw.isBlank() || ToolSourceType.MCP.name().equalsIgnoreCase(raw.trim())) {
            return ToolSourceType.MCP;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "不能通过接口新建内置源");
    }

    /**
     * 筛选用。空值表示不按类型过滤。
     *
     * @param raw 类型名，可空
     * @return 枚举；未传时为 null
     */
    public static ToolSourceType optionalType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ToolSourceType.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的工具源类型");
        }
    }

    /**
     * 空值按 SSE。仅接受 SSE / HTTP（Streamable HTTP），其它直接拒绝。
     *
     * @param raw 传输名，可空
     * @return 规范化后的枚举名
     */
    public static String transport(String raw) {
        if (raw == null || raw.isBlank() || ToolTransport.SSE.name().equalsIgnoreCase(raw.trim())) {
            return ToolTransport.SSE.name();
        }
        if (ToolTransport.HTTP.name().equalsIgnoreCase(raw.trim())) {
            return ToolTransport.HTTP.name();
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "传输仅支持 SSE 或 HTTP");
    }

    /**
     * 只接受 http 和 https。
     *
     * @param raw MCP 远端地址（SSE 入口或 Streamable HTTP 端点）
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
     * 工具源缺省为启用。只接受 1 / 0。
     *
     * @param raw 请求里的状态，可空
     * @return 入库值
     */
    public static int sourceStatus(Integer raw) {
        if (raw == null) {
            return RecordStatus.ENABLED.getCode();
        }
        return exactStatus(raw);
    }

    /**
     * 启停接口必须显式给出 1 或 0。
     *
     * @param raw 目标状态
     * @return 入库值
     */
    public static int exactStatus(Integer raw) {
        if (raw == null || (raw != RecordStatus.ENABLED.getCode() && raw != RecordStatus.DISABLED.getCode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "状态只能是 1 正常或 0 停用");
        }
        return raw;
    }

    /**
     * 未传表示不设置鉴权头。只含空白则拒绝，避免把空格当密钥存进去。
     *
     * @param raw 整段头，可空
     * @return 去掉首尾空白的明文；未传时为 null
     */
    public static String optionalHeader(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "鉴权头不能只包含空白");
        }
        return header(raw);
    }

    /**
     * @param raw 重置时的整段头
     * @return 去掉首尾空白的明文
     */
    public static String requiredHeader(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "鉴权头不能为空");
        }
        return header(raw);
    }

    /**
     * @param raw   原文
     * @param max   最大长度
     * @param label 字段中文名
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
     * @param raw   可空文本
     * @param max   最大长度
     * @param label 字段中文名
     * @return 空白变成 null
     */
    public static String optionalText(String raw, int max, String label) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.length() > max) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "最长 " + max);
        }
        return text;
    }

    /**
     * 空串存 null。非空时必须是合法 JSON。
     *
     * @param raw schema 原文
     * @return 可入库的文本
     */
    public static String schemaJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.length() > SCHEMA_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST, "inputSchemaJson 过长");
        }
        try {
            JSON.readTree(text);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "inputSchemaJson 不是合法 JSON");
        }
        return text;
    }

    private static String header(String raw) {
        String text = raw.trim();
        if (text.length() > HEADER_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST, "鉴权头最长 " + HEADER_MAX);
        }
        return text;
    }
}
