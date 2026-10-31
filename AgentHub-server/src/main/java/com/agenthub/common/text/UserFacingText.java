package com.agenthub.common.text;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 用户可见文本的边界清洗器。所属域：common（横切工具，不含业务判断）。
 * <p>
 * 用途：把工具/基础设施异常里「运维才看得懂」的细节（本机绝对路径、主机端口、异常类名、
 * DEADLINE_EXCEEDED 一类状态码、中间件名）从**会进入模型上下文或用户时间线**的文本里抹掉。
 * 调用方（RAG 检索工具、内置工具、MCP 工具适配）在返回结果字符串之前调用本类，
 * 原始 cause 只进服务端日志。
 * <p>
 * 边界（重要）：
 * <ul>
 *   <li>本类**只**用于失败文案，禁止拿它清洗模型正文或知识库正文——那会误伤用户问题里的
 *       合法数字与文件名；</li>
 *   <li>本类不决定「该说什么话」，只负责「不许出现什么」；固定话术由各域常量给出
 *       （如 {@link #SERVICE_UNAVAILABLE}）；</li>
 *   <li>脱敏是最后一道兜底，不是唯一防线：工具边界应优先返回固定友好句。</li>
 * </ul>
 * 线程安全：无状态，全部为静态纯函数。
 */
public final class UserFacingText {

    /** 其它工具同类基础设施失败的统一话术（按工具域可换「搜索/浏览器暂时不可用」，语义不变） */
    public static final String SERVICE_UNAVAILABLE = "服务暂时不可用，请稍后再试。";

    /** 被抹掉内容的占位，读者能看出「这里本来有东西，但不展示」 */
    public static final String MASK = "（已隐藏）";

    /** 用户面失败文案上限：超长只会让界面更难读，也容易把细节带出来 */
    private static final int MAX_LENGTH = 120;

    /** 盘符绝对路径：D:\Environment\milvus\start.py、C:\Users\... */
    private static final Pattern WINDOWS_PATH = Pattern.compile("[A-Za-z]:\\\\[^\\s，。；、）)\\]】\"'`]*");
    /** POSIX 绝对路径：/opt/milvus/bin、/home/app/config.yml */
    private static final Pattern POSIX_PATH = Pattern.compile(
            "(?<![\\w:/])/(?:opt|usr|home|var|etc|data|root|mnt|srv|tmp|app)/[^\\s，。；、）)\\]】\"'`]*");
    /** 脚本/可执行文件名：start.py、run.sh、restart.bat */
    private static final Pattern SCRIPT_FILE = Pattern.compile("[\\w\\-]+\\.(?:py|sh|bat|ps1|cmd|jar)\\b");
    /** IPv4，可带端口：127.0.0.1、127.0.0.1:19530、10.0.1.7 */
    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?\\b");
    /** 本机/通配主机名，可带端口：localhost:8080、0.0.0.0 */
    private static final Pattern LOCAL_HOST = Pattern.compile("\\b(?:localhost|0\\.0\\.0\\.0)(?::\\d+)?\\b",
            Pattern.CASE_INSENSITIVE);
    /** 内网域名，可带端口：milvus.local:19530、db.internal */
    private static final Pattern PRIVATE_HOST = Pattern.compile(
            "\\b[a-z0-9][a-z0-9\\-.]*\\.(?:local|internal|lan|corp)(?::\\d+)?\\b", Pattern.CASE_INSENSITIVE);
    /** 落单的端口写法：":19530"（前面是空白或句首时才算，避免误伤 10:30 这类时间） */
    private static final Pattern BARE_PORT = Pattern.compile("(?<=\\s|^)[:：]\\d{2,5}\\b");
    /** 异常类名：io.grpc.StatusRuntimeException、RagStoreException */
    private static final Pattern EXCEPTION_NAME = Pattern.compile(
            "\\b(?:[a-z][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error)\\b|\\b[A-Z][\\w$]*(?:Exception|Error)\\b");
    /** gRPC / 框架状态码：DEADLINE_EXCEEDED、UNIMPLEMENTED、RESOURCE_EXHAUSTED */
    private static final Pattern STATUS_CODE = Pattern.compile("\\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\\b");
    /** 中间件/部署细节：Milvus、gRPC、Docker 命令、embedding url */
    private static final Pattern MIDDLEWARE = Pattern.compile(
            "(?i)\\b(?:milvus|grpc|docker(?:[-\\s]compose)?|kubectl|helm|embedding\\s*url)\\b");

    /** 堆栈行：Java 的 "\tat com.foo.Bar(...)" 与 "Caused by:" 一律整行丢弃 */
    private static final List<String> STACK_PREFIXES = List.of("at ", "\tat ", "caused by:", "suppressed:", "... ");

    private UserFacingText() {
    }

    /**
     * 判断异常是否属于「基础设施不可用」类故障（超时 / 连不上 / 熔断 / 通道断）。
     * <p>
     * 只看异常**类型名**与**少量稳定的文案特征**，不解析业务语义：命中即应改用固定友好话术，
     * 而不是把原始 message 漏给用户。
     *
     * @param error 工具或出站抛出的异常，可空；会沿 cause 链查找
     * @return true 表示应按「暂时不可用」处理
     */
    public static boolean isInfraFailure(Throwable error) {
        Throwable cursor = error;
        int guard = 0;
        while (cursor != null && guard++ < 16) {
            String simple = cursor.getClass().getSimpleName();
            if (containsAny(simple, "Timeout", "Socket", "Connect", "UnknownHost", "SSL",
                    "ClosedChannel", "UnresolvedAddress", "StatusRuntime", "StatusException")) {
                return true;
            }
            String message = cursor.getMessage();
            if (message != null && containsAny(message, "DEADLINE_EXCEEDED", "UNAVAILABLE", "UNIMPLEMENTED",
                    "RESOURCE_EXHAUSTED", "连接失败", "连接超时", "同步超时", "读取中断", "地址不合法",
                    "HTTP ", "refused", "timed out")) {
                return true;
            }
            cursor = cursor.getCause() == cursor ? null : cursor.getCause();
        }
        return false;
    }

    /**
     * 抹掉文本里的运维细节，供「必须展示一点原因、又不能泄漏部署信息」的场景使用。
     *
     * @param raw 原始文本（通常是异常 message），可空
     * @return 清洗后的短句；空输入返回空串
     */
    public static String scrub(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        // 逐行过滤：堆栈行整行丢弃，其余行做正则替换，避免把 at com.x.Y(Z.java:12) 留在句子里
        for (String line : raw.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || isStackLine(trimmed)) {
                continue;
            }
            kept.append(trimmed).append('\n');
        }
        String text = kept.toString();
        text = WINDOWS_PATH.matcher(text).replaceAll(MASK);
        text = POSIX_PATH.matcher(text).replaceAll(MASK);
        text = SCRIPT_FILE.matcher(text).replaceAll(MASK);
        text = IPV4.matcher(text).replaceAll(MASK);
        text = LOCAL_HOST.matcher(text).replaceAll(MASK);
        text = PRIVATE_HOST.matcher(text).replaceAll(MASK);
        text = BARE_PORT.matcher(text).replaceAll(MASK);
        text = EXCEPTION_NAME.matcher(text).replaceAll(MASK);
        text = STATUS_CODE.matcher(text).replaceAll(MASK);
        text = MIDDLEWARE.matcher(text).replaceAll("服务");
        text = text.replaceAll("\\s+", " ").trim();
        // 反复替换后可能堆出「（已隐藏）（已隐藏）」，压一遍并去掉句首重复
        text = text.replaceAll("(?:" + Pattern.quote(MASK) + "[，、:：\\s]*){2,}", MASK);
        return text.length() <= MAX_LENGTH ? text : text.substring(0, MAX_LENGTH - 1) + "…";
    }

    /**
     * 生成可展示的失败文案：基础设施故障或清洗后没剩实义内容时，退回固定话术。
     *
     * @param error    原始异常，可空
     * @param fallback 固定友好句（如 {@link #SERVICE_UNAVAILABLE}），不可为空
     * @return 可安全展示的一句话
     */
    public static String safeMessage(Throwable error, String fallback) {
        String safeFallback = fallback == null || fallback.isBlank() ? SERVICE_UNAVAILABLE : fallback;
        if (error == null || isInfraFailure(error)) {
            return safeFallback;
        }
        String message = error.getMessage() == null || error.getMessage().isBlank()
                ? "" : error.getMessage();
        String scrubbed = scrub(message);
        return hasSubstance(scrubbed) ? scrubbed : safeFallback;
    }

    /**
     * 清洗后是否还剩可读内容。
     * <p>
     * 只剩「（已隐藏）」与标点的文本对用户毫无意义，此时应退回固定话术，而不是展示一堆遮罩。
     *
     * @param text 已清洗文本，可空
     * @return true 表示至少还有两个字符的实义内容
     */
    public static boolean hasSubstance(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String core = text.replace(MASK, "");
        int meaningful = 0;
        for (int i = 0; i < core.length(); i++) {
            char ch = core.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                meaningful++;
            }
        }
        return meaningful >= 2;
    }

    private static boolean isStackLine(String line) {
        String lower = line.toLowerCase();
        for (String prefix : STACK_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String text, String... fragments) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String fragment : fragments) {
            if (text.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
