package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.GeneratedFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「文件已生成」标记的写入与清洗。所属域：runtime，纯逻辑。
 *
 * <p>为什么需要标记：工具返回值是模型唯一能看到的出口，SSE 出站层看不到工具原始返回值。
 * 于是由工具在成功文案尾部附加一段结构化标记，运行时的工具包装层与出站层各自解析：
 * <ul>
 *   <li>工具包装层（{@code FileMarkerAgentTool}）：提取后<b>物理剔除</b>标记再交回引擎，
 *       避免这段机械字符污染模型上下文、也不让用户在聊天框里看到乱码；</li>
 *   <li>出站层（{@code TrialChunkWriter}）：兜底再扫一遍，
 *       即便某个工具（如 MCP）自己带了标记，也能出 {@code file_generated} 帧。</li>
 * </ul>
 *
 * <p>形状（字段名即协议，改名属破坏性变更）：
 * <pre>
 * [FILE_GENERATED]{"fileName":"report.md","size":2048}[/FILE_GENERATED]
 * </pre>
 * JSON 由 Jackson 生成，文件名含引号也不会破坏标记结构。
 */
public final class GeneratedFileMarker {

    /** 标记起始串，工具侧只通过 {@link #mark(String, long)} 拼接，禁止手写 */
    public static final String OPEN = "[FILE_GENERATED]";
    /** 标记结束串 */
    public static final String CLOSE = "[/FILE_GENERATED]";

    /**
     * 非贪婪匹配标记内的 JSON：文件名里可能出现 {@code [}、{@code ]}，
     * 因此用 {@code \{.*?}} 而不是 {@code [^}]+}，同时开 DOTALL 容忍模型插入换行。
     */
    private static final Pattern MARKER = Pattern.compile(
            Pattern.quote(OPEN) + "(\\{.*?})" + Pattern.quote(CLOSE), Pattern.DOTALL);

    private static final ObjectMapper JSON = new ObjectMapper();

    private GeneratedFileMarker() {
    }

    /**
     * 一轮清洗结果。
     *
     * @param clean 已剔除全部标记的正文，可直接回灌模型或展示
     * @param files 按出现顺序提取到的产物；没有标记时为空列表
     */
    public record Scan(String clean, List<GeneratedFile> files) {
    }

    /**
     * 拼出给模型看的标记片段。
     *
     * @param fileName 相对工作区根的文件名
     * @param size     写入后的文件字节数
     * @return 形如 {@code [FILE_GENERATED]{"fileName":"a.md","size":12}[/FILE_GENERATED]}
     */
    public static String mark(String fileName, long size) {
        return OPEN + payload(fileName, size) + CLOSE;
    }

    /**
     * 标记内的 JSON 正文。SSE 帧与落库的 FILE 事件都复用它，
     * 保证「实时推送」与「刷新后回放」字段完全一致。
     *
     * @param fileName 相对文件名
     * @param size     字节数
     * @return 紧凑 JSON 串
     */
    public static String payload(String fileName, long size) {
        ObjectNode root = JSON.createObjectNode();
        root.put("fileName", fileName == null ? "" : fileName);
        root.put("size", size);
        return root.toString();
    }

    /**
     * 一次遍历完成「提取 + 剔除」。
     *
     * @param raw 原始正文，可空
     * @return 清洗结果；空输入时 clean 为空串、files 为空列表
     */
    public static Scan scan(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new Scan("", List.of());
        }
        Matcher matcher = MARKER.matcher(raw);
        StringBuilder clean = new StringBuilder(raw.length());
        List<GeneratedFile> files = new ArrayList<>();
        int cursor = 0;
        boolean found = false;
        while (matcher.find()) {
            found = true;
            clean.append(raw, cursor, matcher.start());
            cursor = matcher.end();
            GeneratedFile file = parse(matcher.group(1));
            if (file != null) {
                files.add(file);
            }
        }
        // 连标记串都没有：原样返回，避免无谓拷贝（大段工具结果很常见）
        if (!found && raw.indexOf(OPEN) < 0 && raw.indexOf(CLOSE) < 0) {
            return new Scan(raw, List.of());
        }
        clean.append(raw, cursor, raw.length());
        // 兜底再剔一次残留：结构不完整或 JSON 坏掉的标记（缺闭合标签、半截 JSON）
        // 不会被上面的非贪婪匹配吃掉，若不清理就会原样漏进模型上下文与聊天框。
        // 宁可多删这串机器字符，也不能让它污染对话。
        String stripped = clean.toString().replace(OPEN, "").replace(CLOSE, "");
        // 框架把 String 返回值二次序列化时，正文里的引号与换行都是转义形态。
        // 若不清洗，模型和用户会看到 \" 与 \n 这类残骸；只在「确实整体被序列化」时才反转义。
        stripped = unescapeIfSerialized(stripped);
        // 剔除后可能留下连续空行（标记常独占一行），收一下再交给模型
        return new Scan(stripped.replaceAll("\\n{3,}", "\n\n").trim(), files);
    }

    /**
     * 整段看起来是被 JSON 序列化过的字符串时，反转义回人话。
     *
     * <p>判据是「同时含转义引号与转义换行」：真实的工具输出极少出现这种组合，
     * 而经过 {@code toJson} 的字符串必然两者都有。宁可保守，也不要误改正常的 Markdown。
     *
     * @param text 已剔除标记的正文
     * @return 反转义后的正文；不像被序列化过时原样返回
     */
    private static String unescapeIfSerialized(String text) {
        if (!text.contains("\\\"") || !text.contains("\\n")) {
            return text;
        }
        String unescaped = unescape(text);
        return unescaped == null ? text : unescaped;
    }

    /**
     * 只取产物、不改正文。供只关心「有没有文件」的调用方使用。
     *
     * @param raw 原始正文，可空
     * @return 产物列表，可能为空
     */
    public static List<GeneratedFile> extract(String raw) {
        return scan(raw).files();
    }

    /**
     * 只剔除标记。供必须保持正文其余部分（含缩进）的场景使用。
     *
     * @param raw 原始正文，可空
     * @return 不含标记的正文
     */
    public static String strip(String raw) {
        return scan(raw).clean();
    }

    /**
     * 解析标记内 JSON。字段缺失或形状不对时返回 null，由调用方跳过——
     * 单个坏标记不该让整段工具结果失效。
     *
     * <p>为什么要处理转义形态：AgentScope 的 {@code DefaultToolResultConverter} 收到
     * {@code String} 返回值时会先走 {@code toJson} 再包成 TextBlock，
     * 于是标记里的引号变成 {@code \"}、换行变成 {@code \n}。
     * 直接 {@code readTree} 会失败，产物就被静默丢弃 —— 这正是「文件写成功了却没有下载卡片」的原因。
     * 因此先反转义一次再解析，两条路径都覆盖。
     *
     * @param json 标记内正文，可能是原始 JSON，也可能是被再次序列化后的转义文本
     * @return 产物；无法解析时为 null
     */
    private static GeneratedFile parse(String json) {
        // 先按原样解析（工具未被框架二次序列化的情况，如单测直调）
        GeneratedFile direct = parseLiteral(json);
        if (direct != null) {
            return direct;
        }
        // 再按 JSON 字符串字面量反转义：标记内的引号此时已是 \" 形态，
        // 直接把它当作「带引号字符串的内容」交给 Jackson 还原即可，无需二次转义
        String unescaped = unescape(json);
        return unescaped == null ? null : parseLiteral(unescaped);
    }

    /**
     * 把 JSON 字符串字面量的<b>内容</b>反转义成原始文本。
     *
     * @param escaped 已是转义形态的文本（如 {@code {\"a\":1}}）
     * @return 还原后的文本；无法还原时为 null
     */
    private static String unescape(String escaped) {
        if (escaped == null || escaped.isEmpty()) {
            return null;
        }
        try {
            // 两端补引号即构成合法 JSON 字符串字面量；内容本身已转义，不能再动
            return JSON.readValue("\"" + escaped + "\"", String.class);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 按标准 JSON 解析。
     *
     * @param json 未转义的 JSON 对象文本
     * @return 产物；解析失败时为 null
     */
    private static GeneratedFile parseLiteral(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(json);
            if (node == null || !node.isObject()) {
                return null;
            }
            String fileName = node.path("fileName").asText("");
            if (fileName.isBlank()) {
                return null;
            }
            return new GeneratedFile(fileName, node.path("size").asLong(-1L));
        } catch (Exception ex) {
            return null;
        }
    }
}
