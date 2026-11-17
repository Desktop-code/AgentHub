package com.agenthub.runtime.infra.agentscope.builtin;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 编号生成工具（{@code serialNumberGenerate}）。所属域：runtime 的 infra。
 *
 * <p>职责：按前缀 + 序号规则批量生成编号，如 {@code HT-2026-0001} 到 {@code HT-2026-0010}。
 * 为什么必须走工具：批量编号靠模型逐条"续写"必然错位（跳号、重号、位数不齐），
 * 而编号一旦发给外部就很难收回。
 *
 * <p>规则（写死）：
 * <ul>
 *   <li>编号 = 前缀 + 起始序号（补零到 width 位）；</li>
 *   <li>可选日期段插在前缀与序号之间（{@code yyyyMMdd} / {@code yyyyMM} / {@code yyyy}），
 *       默认不插；插日期段时以服务器当天为准并回显用了哪一天；</li>
 *   <li>序号位数默认 4 位，最小 1 位、最大 10 位；序号的位数不足以容纳「起始 + 数量 - 1」时，
 *       自动放宽位数并在结果里点明，而不是生成出位数不齐的一批。</li>
 * </ul>
 *
 * <p>本工具只生成「文本编号」，不查库、不做唯一性校验：是否已被占用必须由调用方在落库时判。
 * 这一边界写在 description 里，避免模型以为编号天然唯一。
 *
 * <p>线程安全：无状态，纯计算。
 */
@Component
public class SerialNumberTool {

    /** 单次最多生成多少个编号 */
    private static final int MAX_COUNT = 500;

    /**
     * 生成一批编号。
     *
     * @param prefix     编号前缀，如 HT-2026-；可空但不能同时为空前缀又无日期段
     * @param startNo    起始序号（从 1 开始更常见，也允许从 0 开始）
     * @param count      生成个数，1～500
     * @param width      序号补零位数，1～10，缺省 4
     * @param dateSegment 日期段格式：NONE / yyyy / yyyyMM / yyyyMMdd；缺省 NONE
     * @return 中文编号清单；失败时以「编号生成失败：」开头
     */
    @Tool(
            name = "serialNumberGenerate",
            description = "按前缀与序号规则批量生成编号，如 前缀 HT-2026-、起始 1、数量 10、四位补零 → HT-2026-0001…0010。"
                    + "dateSegment 可选 NONE / yyyy / yyyyMM / yyyyMMdd（插在前缀与序号之间，用服务器当天）。"
                    + "只生成文本编号，不查库、不保证唯一：落库前必须自行校验是否已被占用。",
            readOnly = true)
    public String generate(
            @ToolParam(name = "prefix", description = "编号前缀，如 HT-2026-；可空", required = false) String prefix,
            @ToolParam(name = "startNo", description = "起始序号，从 1 开始；缺省 1", required = false)
            @BuiltinParam(minimum = 0, maximum = 99_999_999, defaultValue = "1") Integer startNo,
            @ToolParam(name = "count", description = "生成个数，1～500；缺省 5", required = false)
            @BuiltinParam(minimum = 1, maximum = MAX_COUNT, defaultValue = "5") Integer count,
            @ToolParam(name = "width", description = "序号补零位数，1～10；缺省 4", required = false)
            @BuiltinParam(minimum = 1, maximum = 10, defaultValue = "4") Integer width,
            @ToolParam(name = "dateSegment", description = "日期段：NONE / yyyy / yyyyMM / yyyyMMdd；缺省 NONE", required = false)
            String dateSegment
    ) {
        int start = startNo == null ? 1 : Math.max(0, startNo);
        int total = count == null ? 5 : count;
        if (total < 1 || total > MAX_COUNT) {
            return "编号生成失败：数量必须在 1～" + MAX_COUNT + " 之间。";
        }
        int digits = width == null ? 4 : Math.max(1, Math.min(width, 10));
        String head = prefix == null ? "" : prefix.trim();
        String segment = dateSegment == null || dateSegment.isBlank()
                ? "NONE" : dateSegment.trim().toUpperCase(Locale.ROOT);
        String datePart;
        LocalDate today = LocalDate.now();
        switch (segment) {
            case "NONE" -> datePart = "";
            case "YYYY" -> datePart = today.format(DateTimeFormatter.ofPattern("yyyy"));
            case "YYYYMM" -> datePart = today.format(DateTimeFormatter.ofPattern("yyyyMM"));
            case "YYYYMMDD" -> datePart = today.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            default -> {
                return "编号生成失败：dateSegment 只能是 NONE / yyyy / yyyyMM / yyyyMMdd。";
            }
        }
        if (head.isEmpty() && datePart.isEmpty()) {
            return "编号生成失败：前缀与日期段不能同时为空，否则编号没有可辨识的部分。";
        }
        long lastNo = (long) start + total - 1;
        // 位数不够时装得下为准：宁可 5 位也不生成一批长短不齐的编号
        int effectiveDigits = Math.max(digits, String.valueOf(lastNo).length());
        List<String> numbers = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            long serial = (long) start + i;
            numbers.add(head + datePart + pad(serial, effectiveDigits));
        }
        StringBuilder body = new StringBuilder();
        body.append("编号生成：共 ").append(total).append(" 个\n");
        body.append("- 规则：前缀「").append(head.isEmpty() ? "（无）" : head).append("」");
        if (!datePart.isEmpty()) {
            body.append(" + 日期段 ").append(datePart).append("（").append(segment).append("）");
        }
        body.append(" + 序号 ").append(start).append("～").append(lastNo)
                .append("（").append(effectiveDigits).append(" 位补零）\n");
        if (effectiveDigits > digits) {
            body.append("- 注意：序号已超过 ").append(digits).append(" 位，已自动放宽到 ")
                    .append(effectiveDigits).append(" 位，保证这一批位数一致\n");
        }
        body.append("- 首尾：").append(numbers.get(0)).append(" … ").append(numbers.get(numbers.size() - 1)).append('\n');
        body.append("- 唯一性：本工具只生成文本，未查库；落库前请自行校验是否已被占用\n\n");
        body.append("【编号清单】\n");
        for (String number : numbers) {
            body.append("- ").append(number).append('\n');
        }
        return body.toString().trim();
    }

    /**
     * 左补零到指定位数；超出位数时原样返回（不截断）。
     *
     * @param value  序号
     * @param digits 位数
     * @return 补零后的文本
     */
    private static String pad(long value, int digits) {
        String text = String.valueOf(value);
        if (text.length() >= digits) {
            return text;
        }
        return "0".repeat(digits - text.length()) + text;
    }
}
