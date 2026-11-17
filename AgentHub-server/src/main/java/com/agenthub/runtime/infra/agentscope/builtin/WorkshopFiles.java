package com.agenthub.runtime.infra.agentscope.builtin;

import com.agenthub.runtime.domain.service.GeneratedFileMarker;
import com.agenthub.runtime.domain.service.WorkspaceAccessException;
import io.agentscope.core.agent.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 「车间」类内置工具共用的落盘入口。所属域：runtime 的 infra。
 *
 * <p>为什么单独一个包内工具类：比对 / 汇总 / 扫描 / 填空这些工具都要「读整份文件」和
 * 「另存一份结果」，如果各自拼路径、各自写盘，就会出现「有的工具校验了 .. 、有的没校验」
 * 这类安全洞。这里把「过沙箱 → 判后缀 → 判是否覆盖 → 写盘 → 打产物标记」收成一条路径，
 * 各工具只描述自己的业务语义。
 *
 * <p>安全边界全部委托给 {@link WorkspaceSandbox}（经 {@link WorkspaceFileTool#resolveForTool}
 * 与 {@link WorkspaceFileTool#sandbox()} 取到），本类不自己解析路径。
 *
 * <p>线程安全：无状态，全部静态方法。
 */
final class WorkshopFiles {

    private static final Logger log = LoggerFactory.getLogger(WorkshopFiles.class);

    /** 单次结果文件字节上限：车间产物都是给人看的小文件，超过说明用法不对 */
    private static final long MAX_RESULT_BYTES = 2L * 1024 * 1024;

    private WorkshopFiles() {
    }

    /**
     * 把结果文本写到当前会话工作区，并附带「文件已生成」标记。
     *
     * <p>为什么默认<b>拒绝覆盖</b>：四个车间共用同一个会话工作区，若谁都能随便覆盖，
     * 后跑的车间会把前一个车间的产出冲掉。工具层拒绝覆盖，模型才会换一个不冲突的名字，
     * 这比只在 Skill 里写一句「不要覆盖」更可靠。
     *
     * @param files     已注入的工作区文件工具（提供沙箱）
     * @param context   引擎注入的会话上下文
     * @param fileName  相对文件名，如 数据简报.md
     * @param content   结果正文，UTF-8
     * @param overwrite true 表示允许覆盖同名文件；false 且文件已存在时拒绝写入
     * @return 给模型看的中文结果；失败时以「写入失败：」开头
     */
    static String write(WorkspaceFileTool files, RuntimeContext context, String fileName, String content,
                        boolean overwrite) {
        if (content == null) {
            return "写入失败：内容不能为空。";
        }
        try {
            Path target = files.resolveForTool(context, fileName);
            // 后缀闸门与 workspaceWriteFile 同一份实现：脚本 / 可执行体一律不许落盘
            files.sandbox().rejectExecutable(target.getFileName().toString());
            long incoming = content.getBytes(StandardCharsets.UTF_8).length;
            if (incoming > MAX_RESULT_BYTES) {
                return "写入失败：结果超过单文件上限 2 MB，请精简后再写。";
            }
            if (Files.exists(target) && !overwrite) {
                return "写入失败：/workspace/" + relative(files, context, target)
                        + " 已存在。为避免覆盖其它车间的产出，请换一个文件名（例如加 -v2 或车间后缀）后重试。";
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
            long total = Files.size(target);
            String rel = relative(files, context, target);
            return "文件已成功写入。你可以通过路径 /workspace/" + rel + " 在后续任务中引用它。"
                    + "（写入 " + incoming + " 字节，当前共 " + total + " 字节）"
                    + "\n" + GeneratedFileMarker.mark(rel, total);
        } catch (WorkspaceAccessException rejected) {
            return "写入失败：" + rejected.getMessage();
        } catch (IOException e) {
            // 原始异常可能带本机绝对路径，只进日志
            log.warn("[Agent][workshopWrite] 写入失败 file={} err={}", fileName, e.getMessage(), e);
            return "写入失败：磁盘写入异常，请稍后重试或换一个文件名。";
        }
    }

    /**
     * 计算相对会话工作区根的文件名，用于虚拟路径与产物标记。
     *
     * @param files   工作区文件工具
     * @param context 会话上下文
     * @param target  已经过沙箱校验的绝对路径
     * @return 使用 / 分隔的相对路径
     * @throws WorkspaceAccessException 会话缺失
     */
    static String relative(WorkspaceFileTool files, RuntimeContext context, Path target) {
        Path root = files.sandbox().sessionRoot(context == null ? null : context.getSessionId());
        return WorkspaceSandbox.relativeOf(root, target);
    }

    /**
     * 按行切分文本：兼容 CRLF 与 LF，保留空行由调用方决定是否过滤。
     *
     * @param text 原文，可空
     * @return 行数组；空输入返回空数组
     */
    static String[] lines(String text) {
        if (text == null || text.isEmpty()) {
            return new String[0];
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    }

    /**
     * 把一段可能很长的正文压成单行摘要，供差异 / 命中结果里引用。
     *
     * @param raw   原文，可空
     * @param limit 保留的最大字符数（不小于 8）
     * @return 压平后的短文本；超长以「…」结尾
     */
    static String brief(String raw, int limit) {
        int max = Math.max(8, limit);
        String text = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            return "（空行）";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /**
     * 判断一个单元格 / 字段是否可当作数字解析（允许千分位逗号与首尾空白）。
     *
     * @param raw 原始文本，可空
     * @return 解析结果；无法解析时为空
     */
    static Double number(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim().replace(",", "").replace("，", "");
        // 百分号在汇总里没有统一含义，直接当非数字，避免把 12% 算成 12
        if (text.endsWith("%")) {
            return null;
        }
        try {
            return Double.valueOf(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 数字格式化：整数不带小数点，小数最多保留两位，避免汇总结果出现 3.3333333。
     *
     * @param value 数值
     * @return 供人阅读的数字文本
     */
    static String num(double value) {
        if (Math.abs(value - Math.rint(value)) < 1e-9) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
