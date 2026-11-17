package com.agenthub.runtime.infra.agentscope.builtin;

import com.agenthub.runtime.domain.service.WorkspaceAccessException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板填空工具（{@code templateFill}）。所属域：runtime 的 infra。
 *
 * <p>职责：读一份模板文件，把 {@code {{键}}} 占位替换成给定值，另存为新文件。
 * 为什么走工具：模型自己"重写整份模板"经常顺手改掉标题、编号与格式；
 * 由工具做纯替换才能保证「除占位外一个字都没变」。
 *
 * <p>占位语法（写死）：{@code {{键名}}}，键名允许中英文、数字、下划线、连字符。
 * 兼容全角花括号 {@code ｛｛键名｝｝}，因为样例模板常从 Word 复制出来。
 *
 * <p>关键纪律：
 * <ul>
 *   <li><b>不静默留空</b>：模板里有、调用方没给的占位会原样保留，结果标明「填充未完成」，
 *       避免只换了一两个占位却被说成已经完成；</li>
 *   <li><b>日期规则当场换算</b>：值写成 {@code T+2} / {@code T+2工作日} 时，按今天
 *       （上海时区）换成日期再落盘，不让模型自己心算；</li>
 *   <li><b>不覆盖源模板</b>：输出文件名必须与模板不同，且默认拒绝覆盖已有文件；</li>
 *   <li>输出写在工作区，带「文件已生成」标记，前端可下载。</li>
 * </ul>
 *
 * <p>线程安全：无状态，可并发调用。
 */
@Component
public class TemplateFillTool {

    private static final Logger log = LoggerFactory.getLogger(TemplateFillTool.class);

    /** 占位正则：半角或全角花括号，键名不限中英文 */
    private static final Pattern PLACEHOLDER = Pattern.compile("[{｛]{2}\\s*([^{}｛｝\\s][^{}｛｝]*?)\\s*[}｝]{2}");

    /** 单次最多替换的占位种类（不是次数），防止一次塞进过多无关键值 */
    private static final int MAX_KEYS = 200;

    /** 日期规则的基准日：与平台其它「今天」口径一致，固定上海时区 */
    private static final ZoneId DATE_ZONE = ZoneId.of("Asia/Shanghai");

    private final WorkspaceFileTool files;

    /**
     * @param files 会话工作区文件工具（读模板 + 走同一沙箱写结果）
     */
    public TemplateFillTool(WorkspaceFileTool files) {
        this.files = files;
    }

    /**
     * 用键值填充模板并另存为新文件。
     *
     * @param templateFile 模板文件相对路径，如 通知模板.md
     * @param outputFile   输出文件相对路径，如 通知成稿.md；不得与模板同名
     * @param valuesJson   键值 JSON 对象文本，如 {"部门":"运营部","日期":"2026-11-20"}
     * @param overwrite    true 表示允许覆盖同名输出文件；缺省 false
     * @param context      引擎注入的会话上下文
     * @return 中文填充结果；失败时以「填充失败：」开头
     */
    @Tool(
            name = "templateFill",
            description = "读取模板文件，把 {{键}} 占位替换为给定值并另存为新文件。"
                    + "必须为模板里每一个占位都提供值；只在回复里说将要调用不会写文件。"
                    + "valuesJson 传覆盖全部占位的 JSON 对象。日期值可写 T+2 或 T+2工作日，由本工具按今天换算，不要自己心算。"
                    + "未给值的占位会原样保留，结果标明填充未完成。输出文件名不能与模板相同；默认不覆盖已存在的文件。",
            readOnly = false)
    public String fill(
            @ToolParam(name = "templateFile", description = "模板文件相对路径，如 通知模板.md") String templateFile,
            @ToolParam(name = "outputFile", description = "输出文件相对路径，如 通知成稿.md；不得与模板同名") String outputFile,
            @ToolParam(name = "valuesJson", description = "覆盖全部占位的 JSON，如 {\"标题\":\"通知\",\"日期\":\"T+2工作日\",\"部门\":\"运营部\"}") String valuesJson,
            @ToolParam(name = "overwrite", description = "true 表示允许覆盖同名输出文件；缺省 false", required = false)
            Boolean overwrite,
            RuntimeContext context
    ) {
        if (templateFile == null || templateFile.isBlank()) {
            return "填充失败：请给出模板文件相对路径。";
        }
        if (outputFile == null || outputFile.isBlank()) {
            return "填充失败：请给出输出文件相对路径。";
        }
        String source = templateFile.trim();
        String target = outputFile.trim();
        if (normalizeName(source).equals(normalizeName(target))) {
            return "填充失败：输出文件不能与模板同名，否则会把模板本身改掉。请换一个文件名，如 通知成稿.md。";
        }
        if (valuesJson == null || valuesJson.isBlank()) {
            return "填充失败：请给出键值 JSON，如 {\"部门\":\"运营部\",\"日期\":\"2026-11-20\"}。";
        }
        Map<String, String> values;
        try {
            values = SimpleJson.flatObject(valuesJson);
        } catch (IllegalArgumentException ex) {
            return "填充失败：键值不是合法的扁平 JSON 对象（" + ex.getMessage()
                    + "）。只接受 {\"键\":\"值\"} 或 {\"键\":123} 这类一层的对象。";
        }
        if (values.size() > MAX_KEYS) {
            return "填充失败：键值超过 " + MAX_KEYS + " 项，请先精简。";
        }
        try {
            String template = files.readWholeText(context, source);
            Set<String> requested = new LinkedHashSet<>();
            final int[] replaced = {0};
            Matcher matcher = PLACEHOLDER.matcher(template);
            StringBuilder out = new StringBuilder(template.length());
            // last 记录上一段已拷贝到的位置：占位之间（以及首尾）的原文必须原样保留，
            // 否则「只替换占位、其它一个字不改」的承诺就破了
            int last = 0;
            while (matcher.find()) {
                out.append(template, last, matcher.start());
                last = matcher.end();
                String key = matcher.group(1).trim();
                requested.add(key);
                String value = dated(values.get(key));
                if (value == null) {
                    // 没给值就原样保留占位：静默留空会让交付稿出现空段落却没人察觉
                    out.append(matcher.group());
                    continue;
                }
                out.append(value);
                replaced[0]++;
            }
            out.append(template, last, template.length());
            Set<String> missing = new LinkedHashSet<>();
            for (String key : requested) {
                if (!values.containsKey(key)) {
                    missing.add(key);
                }
            }
            Set<String> unused = new LinkedHashSet<>(values.keySet());
            unused.removeAll(requested);
            String filled = out.toString();
            boolean allowOverwrite = Boolean.TRUE.equals(overwrite);
            String written = WorkshopFiles.write(files, context, target, filled, allowOverwrite);
            if (written.startsWith("写入失败")) {
                return "填充失败：" + written.substring("写入失败：".length());
            }
            StringBuilder body = new StringBuilder();
            // 还有占位没给值就不能称完成：模型看到「完成」会停手，文件里仍留着 {{键}}
            body.append(missing.isEmpty() ? "模板填充完成：" : "填充未完成：")
                    .append(source).append(" → ").append(target).append('\n');
            body.append("- 占位总数：").append(requested.size()).append(" 种；已替换 ").append(replaced[0]).append(" 处\n");
            body.append("- 输出大小：").append(filled.length()).append(" 字符\n");
            if (!missing.isEmpty()) {
                body.append("- 未提供取值（已原样保留占位）：").append(String.join("、", missing)).append('\n');
            }
            if (!unused.isEmpty()) {
                body.append("- 未在模板中出现的键（已忽略）：").append(String.join("、", unused)).append('\n');
            }
            body.append("- 落盘结果：").append(written).append('\n');
            if (!missing.isEmpty()) {
                body.append("\n填充未完成：仍缺 ").append(String.join("、", missing))
                        .append("。请补齐这些键后再次调用本工具");
                if (!allowOverwrite) {
                    body.append("（文件已存在时把 overwrite 设为 true）");
                }
                body.append("。日期不要自己心算，值可以直接写 T+2 或 T+2工作日，由本工具按今天换算。");
            }
            return body.toString().trim();
        } catch (WorkspaceAccessException rejected) {
            return "填充失败：" + rejected.getMessage();
        } catch (RuntimeException ex) {
            log.warn("[Agent][templateFill] 填充异常 template={} output={} err={}",
                    source, target, ex.getMessage(), ex);
            return "填充失败：模板内容无法处理，请确认是 UTF-8 文本文件。";
        }
    }

    /**
     * 文件名归一：只用于判断「输出是否就是模板本身」，不影响实际路径解析。
     *
     * @param name 相对文件名
     * @return 去空白、转小写、统一分隔符的名字
     */
    private static String normalizeName(String name) {
        return name.replace('\\', '/').trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 日期规则换成今天起算的 {@code yyyy-MM-dd}；普通文本原样返回。
     *
     * <p>空值保持 null，让上面的「没给值就保留占位」继续生效。
     * 换算失败（不是规则、偏移过大）也保留原值，不把填空整次打成失败。
     *
     * @param value 调用方给的占位值；可空
     * @return 可写入文件的文本；没给值时 null
     */
    private static String dated(String value) {
        if (value == null) {
            return null;
        }
        String iso = DateOffsetTool.isoOf(LocalDate.now(DATE_ZONE), value.trim());
        return iso == null ? value : iso;
    }

    /**
     * 极简扁平 JSON 解析（只支持 {@code {"键":"值"}} 与数字 / 布尔值）。
     *
     * <p>为什么不引 Jackson：模板填空只需要一层字符串键值，引一个完整 JSON 解析路径
     * 反而让「嵌套对象 / 数组」这类不支持形态的错误更难解释。手写解析可以给出
     * 明确的中文原因（第几个字符出错），而不是抛一句 Jackson 内部异常。
     */
    private static final class SimpleJson {

        private SimpleJson() {
        }

        /**
         * 解析一层对象。
         *
         * @param raw JSON 文本
         * @return 顺序稳定的键值表
         * @throws IllegalArgumentException 不是合法的一层对象
         */
        static Map<String, String> flatObject(String raw) {
            String text = raw.trim();
            if (text.isEmpty() || text.charAt(0) != '{' || text.charAt(text.length() - 1) != '}') {
                throw new IllegalArgumentException("必须是 {...} 形式");
            }
            Map<String, String> values = new LinkedHashMap<>();
            List<String> tokens = splitTopLevel(text.substring(1, text.length() - 1));
            for (String token : tokens) {
                String entry = token.trim();
                if (entry.isEmpty()) {
                    continue;
                }
                int colon = indexOfUnquoted(entry, ':');
                if (colon < 0) {
                    throw new IllegalArgumentException("缺少冒号：" + entry);
                }
                String key = unquote(entry.substring(0, colon).trim());
                String value = unquote(entry.substring(colon + 1).trim());
                if (key.isEmpty()) {
                    throw new IllegalArgumentException("存在空键");
                }
                values.put(key, value);
            }
            return values;
        }

        /**
         * 按顶层逗号切分（忽略引号内的逗号）。
         *
         * @param body 去掉外层花括号的内容
         * @return 各条键值片段
         */
        private static List<String> splitTopLevel(String body) {
            List<String> parts = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < body.length(); i++) {
                char ch = body.charAt(i);
                if (ch == '\\' && quoted && i + 1 < body.length()) {
                    current.append(ch).append(body.charAt(i + 1));
                    i++;
                    continue;
                }
                if (ch == '"') {
                    quoted = !quoted;
                    current.append(ch);
                    continue;
                }
                if (ch == ',' && !quoted) {
                    parts.add(current.toString());
                    current.setLength(0);
                    continue;
                }
                current.append(ch);
            }
            if (current.length() > 0) {
                parts.add(current.toString());
            }
            return parts;
        }

        /**
         * 找第一个不在引号内的字符位置。
         *
         * @param text  文本
         * @param target 目标字符
         * @return 下标；没有为 -1
         */
        private static int indexOfUnquoted(String text, char target) {
            boolean quoted = false;
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == '\\' && quoted && i + 1 < text.length()) {
                    i++;
                    continue;
                }
                if (ch == '"') {
                    quoted = !quoted;
                } else if (ch == target && !quoted) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * 去引号并还原常见转义；数字 / 布尔原样返回。
         *
         * @param raw 片段
         * @return 实际字符串值
         */
        private static String unquote(String raw) {
            String text = raw.trim();
            if (text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"') {
                String inner = text.substring(1, text.length() - 1);
                StringBuilder out = new StringBuilder(inner.length());
                for (int i = 0; i < inner.length(); i++) {
                    char ch = inner.charAt(i);
                    if (ch != '\\' || i + 1 >= inner.length()) {
                        out.append(ch);
                        continue;
                    }
                    char next = inner.charAt(++i);
                    switch (next) {
                        case 'n' -> out.append('\n');
                        case 't' -> out.append('\t');
                        case 'r' -> out.append('\r');
                        default -> out.append(next);
                    }
                }
                return out.toString();
            }
            if ("null".equalsIgnoreCase(text)) {
                return "";
            }
            return text;
        }
    }
}
