package com.agenthub.runtime.domain.service;

import java.util.regex.Pattern;

/**
 * 失败原因的脱敏摘要。所属域：runtime。
 *
 * <p>用途：控制台日志已经用 {@code log.warn("...", ex)} 打全栈，但调用追踪的扩展信息（attrs_json）
 * 只能放短文本。本类把异常压成「类名 + message + 少量本仓帧」，让「模型连通测成功、试跑却失败」
 * 这类只看得到 {@code type=TrialFailure} 的问题，能在追踪面板里直接看到真正的 cause。
 *
 * <p>安全边界（硬性）：
 * <ol>
 *   <li><b>只读不写</b>：不修改异常、不重试、不参与成功判定，纯字符串处理；</li>
 *   <li>禁止 API Key / Authorization / 长 base64 / data URI 原文进摘要，命中即抹掉；</li>
 *   <li>只保留 {@code com.agenthub} 开头的帧（类名 + 方法名 + 行号），不写绝对路径；</li>
 *   <li>用户可见文案仍走原清洗链（{@link TrialTexts} / {@code UserFacingText}），本类结果只进日志与追踪。</li>
 * </ol>
 *
 * 线程安全：无状态，全部静态纯函数。
 */
public final class CauseDigest {

    /** cause 链最多展开几层：再深只是框架包装，对定位没有增量信息 */
    private static final int MAX_DEPTH = 4;
    /** 单条 message 上限，避免某家供应商把整段响应体塞进 message */
    private static final int MAX_MESSAGE = 120;
    /** 整段摘要上限（attrs_json 是 TEXT，但仍要保证面板一行能读完） */
    private static final int MAX_TOTAL = 600;
    /** 最多带几帧本仓栈帧 */
    private static final int MAX_FRAMES = 3;
    /** cause 层级分隔符，便于人一眼看出包装顺序 */
    private static final String CAUSE_JOIN = " <- ";
    /** 本仓包前缀：只保留这些帧，框架与 JDK 帧对定位业务没有帮助 */
    private static final String OWN_PACKAGE = "com.agenthub";

    /** data URI 或超长 base64 整段抹掉：图片/附件原文绝不进日志与追踪 */
    private static final Pattern BASE64_BLOB = Pattern.compile(
            "(?:data:[\\w.+-]+/[\\w.+-]+;base64,)?[A-Za-z0-9+/]{120,}={0,2}");
    /** 请求头形态的密钥：Authorization: Bearer xxx、x-api-key=xxx */
    private static final Pattern HEADER_SECRET = Pattern.compile(
            "(?i)(authorization|api[-_]?key|x-api-key)\\s*[:=]\\s*\\S+", Pattern.CASE_INSENSITIVE);

    private CauseDigest() {
    }

    /**
     * 生成脱敏摘要：{@code 外层类名: message <- 内层类名: message @帧…}。
     *
     * @param error 原始异常，可空
     * @return 不超过 600 字的摘要；入参为空时返回 null（调用方不必再判空）
     */
    public static String of(Throwable error) {
        if (error == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        Throwable cursor = error;
        int depth = 0;
        while (cursor != null && depth < MAX_DEPTH) {
            if (depth > 0) {
                text.append(CAUSE_JOIN);
            }
            text.append(cursor.getClass().getSimpleName());
            String message = clean(cursor.getMessage());
            if (!message.isEmpty()) {
                text.append(": ").append(clip(message, MAX_MESSAGE));
            }
            Throwable next = cursor.getCause();
            // 自引用 cause（部分框架会把自己挂成 cause）会导致死循环，这里显式截断
            if (next == null || next == cursor) {
                break;
            }
            cursor = next;
            depth++;
        }
        String frames = ownFrames(error);
        if (!frames.isEmpty()) {
            text.append(' ').append(frames);
        }
        return clip(text.toString(), MAX_TOTAL);
    }

    /**
     * 截取本仓栈帧；若整条链都没有本仓帧则退回最外层的前几帧。
     *
     * @param error 原始异常
     * @return 形如 {@code at com.x.Y.z(Y.java:12)} 的片段；没有栈时为空串
     */
    private static String ownFrames(Throwable error) {
        StringBuilder ours = new StringBuilder();
        StringBuilder fallback = new StringBuilder();
        Throwable cursor = error;
        int depth = 0;
        int oursCount = 0;
        int fallbackCount = 0;
        while (cursor != null && depth < MAX_DEPTH) {
            StackTraceElement[] frames = cursor.getStackTrace();
            if (frames != null) {
                for (StackTraceElement frame : frames) {
                    if (frame == null) {
                        continue;
                    }
                    String className = frame.getClassName() == null ? "" : frame.getClassName();
                    if (className.startsWith(OWN_PACKAGE)) {
                        if (oursCount < MAX_FRAMES) {
                            ours.append(oursCount == 0 ? "" : " ").append(format(frame));
                            oursCount++;
                        }
                    } else if (fallbackCount < MAX_FRAMES) {
                        fallback.append(fallbackCount == 0 ? "" : " ").append(format(frame));
                        fallbackCount++;
                    }
                }
            }
            Throwable next = cursor.getCause();
            if (next == null || next == cursor) {
                break;
            }
            cursor = next;
            depth++;
        }
        return oursCount > 0 ? ours.toString() : fallback.toString();
    }

    /**
     * @param frame 栈帧
     * @return 单帧文本；只写类名、方法名与行号，不含文件系统路径
     */
    private static String format(StackTraceElement frame) {
        return "at " + frame.getClassName() + "." + frame.getMethodName()
                + "(" + frame.getFileName() + ":" + frame.getLineNumber() + ")";
    }

    /**
     * 清洗单条 message：折行、抹掉密钥与超长 base64。
     *
     * @param raw 原始 message，可空
     * @return 单行摘要；无内容时为空串
     */
    private static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        // 先抹 data URI / 长 base64：否则后续正则要在大段字符上反复回溯
        text = BASE64_BLOB.matcher(text).replaceAll("***");
        text = HEADER_SECRET.matcher(text).replaceAll("$1=***");
        // 复用试跑既有脱敏规则（sk- / bearer / api_key= / 密文二元组），与事件摘要同一口径
        text = TrialTexts.redact(text);
        return text.replaceAll("\\s+", " ").trim();
    }

    /**
     * @param text 已清洗文本
     * @param max  最大字符数
     * @return 截断结果，超长补省略号
     */
    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String value = text.trim();
        if (value.length() <= max) {
            return value;
        }
        return max <= 1 ? "…" : value.substring(0, max - 1) + "…";
    }
}
