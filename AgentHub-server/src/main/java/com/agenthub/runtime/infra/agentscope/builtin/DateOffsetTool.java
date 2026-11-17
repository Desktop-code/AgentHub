package com.agenthub.runtime.infra.agentscope.builtin;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日期推算工具（{@code dateOffset}）。所属域：runtime 的 infra。
 *
 * <p>职责：给定基准日与规则（如 {@code T+2}、{@code T+3工作日}），算出目标日期并说明推算口径。
 * 为什么必须走工具：模型算日期极易算错（跨月、跨年、周末），而这类错误在合规场景代价很高。
 *
 * <p>规则语法（写死，避免「差不多」的歧义）：
 * <ul>
 *   <li>{@code T+2} / {@code +2} / {@code 2}：往后 2 个<b>自然日</b>；</li>
 *   <li>{@code T+2工作日} / {@code T+2个工作日} / {@code 2个工作日}：往后 2 个<b>工作日</b>；</li>
 *   <li>{@code T-1} / {@code -1工作日}：往前推算，语义对称；</li>
 *   <li>大小写与空格不敏感；不认其它写法（如「下周三」），一律给可读原因而不是猜。</li>
 * </ul>
 *
 * <p>节假日口径：默认只把周六周日当非工作日；调用方可用 {@code holidays} 传入放假日、
 * 用 {@code extraWorkdays} 传入调休上班日。这两项是参数而非常量，避免把某一年度的
 * 法定节假日写进代码——那会过期且无法覆盖不同地区。
 *
 * <p>线程安全：无状态，纯计算。
 */
@Component
public class DateOffsetTool {

    /** 支持的最大偏移量：约十年，足够覆盖合同 / 账期场景，同时挡住死循环式输入 */
    private static final int MAX_OFFSET = 3_660;

    /** 规则匹配：可选 T 前缀 + 正负号 + 数字 + 可选单位 */
    private static final Pattern RULE = Pattern.compile(
            "^[Tt]?\\s*([+-])?\\s*(\\d{1,4})\\s*(自然日|日历日|工作日|个工作日)?$");

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 按规则推算日期。
     *
     * @param baseDate      基准日，格式 yyyy-MM-dd，如 2026-11-16
     * @param rule          推算规则，如 T+2、T+3工作日
     * @param holidays      放假日，逗号分隔的 yyyy-MM-dd；可空
     * @param extraWorkdays 调休上班日，逗号分隔的 yyyy-MM-dd（落在周末也算工作日）；可空
     * @return 中文推算说明；失败时以「日期推算失败：」开头
     */
    @Tool(
            name = "dateOffset",
            description = "按基准日与规则推算目标日期，并说明口径。rule 支持 T+2（自然日）、T+3工作日（跳过周末）、"
                    + "T-1（往前）。可选 holidays 传放假日、extraWorkdays 传调休上班日（逗号分隔 yyyy-MM-dd）。"
                    + "日期计算必须调用本工具，禁止自己心算。",
            readOnly = true)
    public String offset(
            @ToolParam(name = "baseDate", description = "基准日，格式 yyyy-MM-dd，如 2026-11-16") String baseDate,
            @ToolParam(name = "rule", description = "推算规则，如 T+2、T+3工作日、T-1；单位缺省按自然日") String rule,
            @ToolParam(name = "holidays", description = "放假日，逗号分隔的 yyyy-MM-dd；可空", required = false)
            String holidays,
            @ToolParam(name = "extraWorkdays", description = "调休上班日，逗号分隔的 yyyy-MM-dd；可空", required = false)
            String extraWorkdays
    ) {
        if (baseDate == null || baseDate.isBlank()) {
            return "日期推算失败：请给出基准日，格式 yyyy-MM-dd。";
        }
        if (rule == null || rule.isBlank()) {
            return "日期推算失败：请给出规则，如 T+2 或 T+3工作日。";
        }
        LocalDate base;
        try {
            base = LocalDate.parse(baseDate.trim(), ISO);
        } catch (DateTimeParseException ex) {
            return "日期推算失败：基准日「" + baseDate.trim() + "」不是合法日期，请用 yyyy-MM-dd，如 2026-11-16。";
        }
        Matcher matcher = RULE.matcher(rule.trim());
        if (!matcher.matches()) {
            return "日期推算失败：规则「" + rule.trim()
                    + "」无法识别。只支持 T+2 / T+3工作日 / T-1 这类写法（正负号 + 数字 + 可选「工作日」）。";
        }
        boolean backward = "-".equals(matcher.group(1));
        int amount = Integer.parseInt(matcher.group(2));
        String unit = matcher.group(3) == null ? "" : matcher.group(3);
        boolean workday = unit.endsWith("工作日");
        if (amount > MAX_OFFSET) {
            return "日期推算失败：偏移量不能超过 " + MAX_OFFSET + " 天。";
        }
        Set<LocalDate> offDays = parseDates(holidays);
        Set<LocalDate> extra = parseDates(extraWorkdays);
        if (amount == 0) {
            return "日期推算：" + base + "（" + weekday(base) + "）按规则「" + rule.trim()
                    + "」偏移 0 天，结果仍是 " + base + "（" + weekday(base) + "）。";
        }
        int signed = backward ? -amount : amount;
        LocalDate result = workday
                ? shiftWorkdays(base, signed, offDays, extra)
                : base.plusDays(signed);
        if (result == null) {
            return "日期推算失败：按工作日规则推算时进入了无效区间，请检查放假日与调休日是否重叠过多。";
        }
        StringBuilder body = new StringBuilder();
        body.append("日期推算：").append(base).append("（").append(weekday(base)).append("）")
                .append(' ').append(signed > 0 ? "+" : "-").append(amount)
                .append(workday ? " 个工作日" : " 个自然日")
                .append(" = ").append(result).append("（").append(weekday(result)).append("）\n");
        body.append("- 规则原文：").append(rule.trim()).append('\n');
        body.append("- 口径：").append(workday
                ? "跳过周六、周日" + (offDays.isEmpty() ? "" : "与指定的 " + offDays.size() + " 个放假日")
                        + (extra.isEmpty() ? "" : "，并把指定的 " + extra.size() + " 个调休日按工作日计")
                : "按日历自然日连续推算，不跳过周末与节假日");
        body.append('\n');
        if (workday) {
            body.append("- 推算过程：从 ").append(base).append(" 起，逐日").append(signed > 0 ? "往后" : "往前")
                    .append("数满 ").append(amount).append(" 个工作日（基准日本身不计入）\n");
        }
        return body.toString().trim();
    }

    /**
     * 把填空值里的日期规则换成 {@code yyyy-MM-dd}。
     *
     * <p>模板填空把「T+2工作日」这类值交给这里，避免模型自己心算后再写入文件。
     * 不属于本工具规则语法、偏移过大或推算失败时返回 null，调用方保留原值。
     * 不带放假日：填空只跳过周末；需要法定节假日时先调 {@link #offset}，再把算出的日期填进去。
     *
     * @param base 基准日；空则无法换算
     * @param rule 填空值，如 T+2、T+2工作日
     * @return 目标日；无法识别时 null
     */
    public static String isoOf(LocalDate base, String rule) {
        if (base == null || rule == null || rule.isBlank()) {
            return null;
        }
        Matcher matcher = RULE.matcher(rule.trim());
        if (!matcher.matches()) {
            return null;
        }
        boolean backward = "-".equals(matcher.group(1));
        int amount = Integer.parseInt(matcher.group(2));
        String unit = matcher.group(3) == null ? "" : matcher.group(3);
        boolean workday = unit.endsWith("工作日");
        if (amount > MAX_OFFSET) {
            return null;
        }
        if (amount == 0) {
            return base.toString();
        }
        int signed = backward ? -amount : amount;
        Set<LocalDate> none = Set.of();
        LocalDate result = workday ? shiftWorkdays(base, signed, none, none) : base.plusDays(signed);
        return result == null ? null : result.toString();
    }

    /**
     * 按工作日偏移：逐日推进，只数工作日；基准日不计入偏移量。
     *
     * @param base     基准日
     * @param amount   正负偏移量（工作日数）
     * @param offDays  放假日
     * @param extra    调休上班日
     * @return 目标日；越过可推范围时 null
     */
    private static LocalDate shiftWorkdays(LocalDate base, int amount, Set<LocalDate> offDays, Set<LocalDate> extra) {
        LocalDate cursor = base;
        int remaining = Math.abs(amount);
        int guard = 0;
        int step = amount > 0 ? 1 : -1;
        // 上限按「最坏情况 2 倍自然日 + 放假日」给足余量，避免放假日过多时提前退出
        int limit = (amount + offDays.size() + 7) * 3 + 30;
        while (remaining > 0) {
            if (++guard > limit) {
                return null;
            }
            cursor = cursor.plusDays(step);
            if (isWorkday(cursor, offDays, extra)) {
                remaining--;
            }
        }
        return cursor;
    }

    /**
     * 是否工作日：调休上班日优先，其次排除放假日与周末。
     *
     * @param date   待判日期
     * @param offDays 放假日
     * @param extra   调休上班日
     * @return true 表示算作工作日
     */
    private static boolean isWorkday(LocalDate date, Set<LocalDate> offDays, Set<LocalDate> extra) {
        if (extra.contains(date)) {
            return true;
        }
        if (offDays.contains(date)) {
            return false;
        }
        return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    /**
     * 解析逗号分隔的日期列表；无法解析的项静默忽略（不因为一个脏日期整体失败）。
     *
     * @param raw 逗号分隔的 yyyy-MM-dd
     * @return 日期集合；空输入为空集
     */
    private static Set<LocalDate> parseDates(String raw) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return dates;
        }
        for (String item : raw.split("[,，、;；\\s]+")) {
            String text = item.trim();
            if (text.isEmpty()) {
                continue;
            }
            try {
                dates.add(LocalDate.parse(text, ISO));
            } catch (DateTimeParseException ignored) {
                // 单个脏日期不影响整体推算：忽略即可，结果里只报有效条目数量
            }
        }
        return dates;
    }

    /**
     * @param date 日期
     * @return 中文星期
     */
    private static String weekday(LocalDate date) {
        return switch (date.getDayOfWeek()) {
            case MONDAY -> "周一";
            case TUESDAY -> "周二";
            case WEDNESDAY -> "周三";
            case THURSDAY -> "周四";
            case FRIDAY -> "周五";
            case SATURDAY -> "周六";
            case SUNDAY -> "周日";
        };
    }
}
