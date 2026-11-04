package com.agenthub.runtime.infra.agentscope.builtin;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import com.agenthub.runtime.domain.service.GeneratedFileMarker;
import com.agenthub.runtime.domain.service.WorkspaceAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 会话级文件读写工具，用于 Context Offloading：长文本落盘，只在对话里留路径。所属域：runtime 的 infra。
 * <p>
 * 三个 {@code @Tool(name)} 须与 tool_item.name 一致：workspaceWriteFile / workspaceReadFile / workspaceListFiles。
 * 同一 Bean 的多个 @Tool 会在注册时被一起挂上（见 {@code BuiltinToolkitAssembler}），
 * 因此绑定其中任意一个即视为启用整套文件能力，种子 SQL 三行应同时启用或同时停用。
 * <p>
 * <b>安全边界不在本类</b>：路径校验、沙箱前缀、可执行后缀黑名单全部由 {@link WorkspaceSandbox} 负责。
 * 本类只做「工具语义」——参数解释、行号渲染、截断提示与中文失败文案。
 * 这样「模型主动落盘」与「运行时把超长工具结果自动落盘」共用同一套安全规则，
 * 不会出现一侧禁了脚本后缀、另一侧忘了禁的洞。
 * <p>
 * 会话标识由 {@link RuntimeContext} 隐式注入，<b>不</b>作为工具参数暴露给模型，模型无法指定别人的目录。
 * Hutool 未在本模块引入，统一用 {@code java.nio.file.Files} 实现，避免为一个小工具多引一个依赖。
 */
@Component
public class WorkspaceFileTool {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceFileTool.class);

    /** 对模型展示的虚拟路径前缀；与沙箱口径保持一致 */
    private static final String VIRTUAL_PREFIX = WorkspaceSandbox.VIRTUAL_PREFIX;
    /** 单次读取的字符上限：超出即截断，靠 start_line 续读 */
    private static final int MAX_READ_CHARS = 2000;
    /** 单次读取的行数上限 */
    private static final int MAX_READ_LINES = 100;
    /** 单次写入后文件总字节上限，防止模型把整个上下文反复落盘把磁盘写满 */
    private static final long MAX_WRITE_BYTES = 4L * 1024 * 1024;
    /** 目录列表最多返回条数，避免一次刷屏挤爆上下文 */
    private static final int MAX_LIST_ENTRIES = 200;
    /** 目录列表递归深度：允许子目录，但禁止深层嵌套 */
    private static final int MAX_LIST_DEPTH = 4;

    /** 所有落盘与读取路径的唯一闸门 */
    private final WorkspaceSandbox sandbox;

    /**
     * 复用已构建的沙箱（工具结果句柄化与文件工具必须指向同一个根）。
     * <p>
     * 只此一个构造器：沙箱是单例 Bean，两处落盘必须同一根目录，
     * 否则「写进 A 目录、读时找 B 目录」会让模型以为文件凭空消失。
     *
     * @param sandbox 沙箱
     */
    @Autowired
    public WorkspaceFileTool(WorkspaceSandbox sandbox) {
        this.sandbox = sandbox;
    }

    /**
     * @return 共用的沙箱，供下载接口与句柄化使用
     */
    public WorkspaceSandbox sandbox() {
        return sandbox;
    }

    /**
     * 写入文本到当前会话工作区。
     *
     * @param fileName        相对 /workspace/ 的文件名，可带子目录（如 notes/plan.md）；禁止绝对路径与 ..
     * @param content         要写入的文本，UTF-8；为空写空文件
     * @param append          true 追加到末尾，false 或缺省覆盖写
     * @param runtimeContext  引擎注入的会话上下文，只用于取 sessionId，不进 JSON Schema
     * @return 给模型看的成功/失败文本；任何 IO 异常都转成可读中文，不抛给引擎
     */
    @Tool(
            name = "workspaceWriteFile",
            description = "把长文本写入当前会话的工作区文件，返回可引用的虚拟路径 /workspace/文件名。"
                    + "适用于把超长的中间结果、清单、草稿落盘，避免占满对话上下文；"
                    + "随后用 workspaceReadFile 按行号精确读取。只允许相对文件名，禁止脚本与可执行后缀。",
            readOnly = false,
            concurrencySafe = false)
    public String writeFile(
            @ToolParam(name = "fileName", description = "相对文件名，可带子目录，如 notes/plan.md；不要以 / 开头，不要含 ..") String fileName,
            @ToolParam(name = "content", description = "要写入的文本内容（UTF-8）") String content,
            @ToolParam(
                    name = "append",
                    description = "true 表示追加到已有文件末尾；false 或不传表示覆盖写入",
                    required = false)
            Boolean append,
            RuntimeContext runtimeContext
    ) {
        if (content == null) {
            return "写入失败：content 不能为空；确实要清空文件请传入空字符串。";
        }
        try {
            Path root = sandbox.sessionRoot(sessionId(runtimeContext));
            Path target = sandbox.resolveInside(root, fileName);
            // 后缀校验放在规范化之后：先确认路径合法，再看要落盘的到底是什么类型
            sandbox.rejectExecutable(target.getFileName().toString());

            boolean appending = Boolean.TRUE.equals(append);
            // 父目录按需创建；target 已通过前缀校验，这里创建的目录必然落在沙箱内
            Files.createDirectories(target.getParent());

            long existing = Files.isRegularFile(target) ? Files.size(target) : 0L;
            long incoming = content.getBytes(StandardCharsets.UTF_8).length;
            if (existing + incoming > MAX_WRITE_BYTES) {
                return "写入失败：文件会超过单文件上限 " + formatBytes(MAX_WRITE_BYTES)
                        + "，请拆成多个文件或先精简内容。";
            }

            StandardOpenOption[] options = appending
                    ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND}
                    : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING};
            Files.writeString(target, content, StandardCharsets.UTF_8, options);

            String virtualPath = VIRTUAL_PREFIX + WorkspaceSandbox.relativeOf(root, target);
            long totalSize = Files.size(target);
            // 首句按约定文案原样返回，附注只补「追加还是覆盖」与当前体积，便于模型判断要不要继续写
            String text = "文件已成功写入。你可以通过路径 " + virtualPath + " 在后续任务中引用它。"
                    + "（" + (appending ? "追加" : "覆盖") + "写入 " + incoming + " 字节，当前共 "
                    + totalSize + " 字节）";
            // 产物标记：运行时会在工具返回给引擎之前物理剔除，模型看不到这段机械字符；
            // 它只是给「SSE / 事件」层识别「本轮生成了哪个文件」用的结构化信号。
            // 体积取写入后的真实字节数，而不是 incoming：追加写入时前者才是可下载的完整大小。
            return text + "\n" + GeneratedFileMarker.mark(WorkspaceSandbox.relativeOf(root, target), totalSize);
        } catch (WorkspaceAccessException rejected) {
            return "写入失败：" + rejected.getMessage();
        } catch (IOException e) {
            // 原始异常含本机绝对路径，只进日志；模型侧只给可行动的一句话
            log.warn("[Agent][workspaceWriteFile] 写入失败 err={}", e.getMessage(), e);
            return "写入失败：磁盘写入异常，请稍后重试或换一个文件名。";
        }
    }

    /**
     * 按行号读取当前会话工作区的文件。
     *
     * @param fileName       相对文件名，与写入时一致
     * @param startLine      起始行号，从 1 开始，缺省 1；用于接续上次被截断的位置
     * @param maxLines       本次最多返回多少行，缺省 100，上限 100
     * @param runtimeContext 引擎注入的会话上下文
     * @return 带 {@code 行号 | 正文} 前缀的内容；被截断时末尾附续读指引
     */
    @Tool(
            name = "workspaceReadFile",
            description = "读取当前会话工作区文件的指定行范围，返回带行号的内容（格式：行号 | 正文）。"
                    + "单次最多返回 2000 字符或 100 行，超出会截断并提示用 startLine 继续读。"
                    + "适用于读取之前用 workspaceWriteFile 落盘的长文本，或工具结果被截断后落盘的文件。",
            readOnly = true)
    public String readFile(
            @ToolParam(name = "fileName", description = "相对文件名，可带子目录，如 notes/plan.md") String fileName,
            @ToolParam(name = "startLine", description = "从第几行开始读，从 1 开始，缺省 1", required = false)
            @BuiltinParam(minimum = 1, defaultValue = "1") Integer startLine,
            @ToolParam(name = "maxLines", description = "本次最多读多少行，缺省 100，上限 100", required = false)
            @BuiltinParam(minimum = 1, maximum = MAX_READ_LINES, defaultValue = "100") Integer maxLines,
            RuntimeContext runtimeContext
    ) {
        int from = startLine == null || startLine < 1 ? 1 : startLine;
        int limit = maxLines == null || maxLines < 1 ? MAX_READ_LINES : Math.min(maxLines, MAX_READ_LINES);
        try {
            Path root = sandbox.sessionRoot(sessionId(runtimeContext));
            Path target = sandbox.resolveInside(root, fileName);
            if (!Files.isRegularFile(target)) {
                // 区分「不存在」和「是目录」：后者多半是模型把目录名当文件名传了，提示方式不同
                if (Files.isDirectory(target)) {
                    return "读取失败：这是一个目录，不能当文件读取。请用 workspaceListFiles 查看其中的文件。";
                }
                return "读取失败：文件不存在。请先用 workspaceListFiles 确认工作区里有哪些文件。";
            }

            String rel = WorkspaceSandbox.relativeOf(root, target);
            StringBuilder body = new StringBuilder();
            body.append("文件 ").append(VIRTUAL_PREFIX).append(rel).append(" 的内容（行号 | 正文）：\n");

            int lineNo = 0;
            int emitted = 0;
            int used = 0;
            // resumeAt 非空表示还有没读到的内容，作为下次的 startLine 回给模型
            Integer resumeAt = null;
            String oversizeNote = null;
            try (BufferedReader reader = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lineNo++;
                    // 起始行之前的内容直接跳过，不占字符预算
                    if (lineNo < from) {
                        continue;
                    }
                    if (emitted >= limit) {
                        resumeAt = lineNo;
                        break;
                    }
                    String prefix = lineNo + " | ";
                    int cost = prefix.length() + line.length() + 1;
                    if (used + cost > MAX_READ_CHARS) {
                        int room = MAX_READ_CHARS - used - prefix.length() - 1;
                        if (room > 0) {
                            // 整行放不下：只裁这一行，仍带出行号，并明确告知这行被裁过，避免模型以为文件内容就这么多
                            String text = line.length() <= room ? line : line.substring(0, room);
                            body.append(prefix).append(text).append('\n');
                            emitted++;
                            if (text.length() < line.length()) {
                                oversizeNote = "第 " + lineNo + " 行过长，已截断显示前 " + text.length() + " 个字符";
                            }
                            resumeAt = lineNo + 1;
                        } else {
                            // 字符预算已耗尽：这一行整体留到下一轮，行号不推进
                            resumeAt = lineNo;
                        }
                        break;
                    }
                    body.append(prefix).append(line).append('\n');
                    used += cost;
                    emitted++;
                }
            }

            if (emitted == 0) {
                return "读取失败：start_line=" + from + " 已超出文件末尾（该文件共 " + lineNo + " 行）。";
            }
            if (oversizeNote != null) {
                body.append("…（").append(oversizeNote).append("）\n");
            }
            if (resumeAt != null) {
                body.append("…（内容过长已截断，请使用 start_line=").append(resumeAt).append(" 继续读取）");
            }
            return body.toString();
        } catch (WorkspaceAccessException rejected) {
            return "读取失败：" + rejected.getMessage();
        } catch (IOException e) {
            log.warn("[Agent][workspaceReadFile] 读取失败 err={}", e.getMessage(), e);
            return "读取失败：文件读取异常，请稍后重试。";
        }
    }

    /**
     * 列出当前会话工作区里的文件。
     *
     * @param runtimeContext 引擎注入的会话上下文
     * @return 文件名（含子目录相对路径）与大小，按名称排序；空目录时明确告知
     */
    @Tool(
            name = "workspaceListFiles",
            description = "列出当前会话工作区里的所有文件及大小，用来确认之前落盘了哪些文件、该读哪个。无需参数。",
            readOnly = true)
    public String listFiles(RuntimeContext runtimeContext) {
        try {
            Path root = sandbox.sessionRoot(sessionId(runtimeContext));
            // 只列普通文件；不跟随符号链接（walk 默认不跟随），避免把沙箱外的目录带出来
            List<Path> files = new ArrayList<>();
            boolean overflow = false;
            try (Stream<Path> walk = Files.walk(root, MAX_LIST_DEPTH)) {
                List<Path> all = walk.filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(path -> WorkspaceSandbox.relativeOf(root, path).toLowerCase(Locale.ROOT)))
                        .toList();
                for (Path path : all) {
                    if (files.size() >= MAX_LIST_ENTRIES) {
                        overflow = true;
                        break;
                    }
                    files.add(path);
                }
            }

            if (files.isEmpty()) {
                return "当前会话工作区还没有任何文件。可以先用 workspaceWriteFile 落盘长文本。";
            }

            StringBuilder body = new StringBuilder();
            body.append("当前会话工作区共 ").append(files.size()).append(" 个文件（路径前缀 ")
                    .append(VIRTUAL_PREFIX).append("）：\n");
            for (Path path : files) {
                long size;
                try {
                    size = Files.size(path);
                } catch (IOException e) {
                    // 单个文件取大小失败不影响整份清单；用占位说明，不返回异常原文
                    size = -1L;
                }
                body.append("- ").append(VIRTUAL_PREFIX).append(WorkspaceSandbox.relativeOf(root, path))
                        .append("（").append(size < 0 ? "大小未知" : formatBytes(size)).append("）\n");
            }
            if (overflow) {
                body.append("…（文件过多，仅列出前 ").append(MAX_LIST_ENTRIES).append(" 个）");
            }
            return body.toString().trim();
        } catch (WorkspaceAccessException rejected) {
            return "列出文件失败：" + rejected.getMessage();
        } catch (IOException e) {
            log.warn("[Agent][workspaceListFiles] 列目录失败 err={}", e.getMessage(), e);
            return "列出文件失败：工作区目录读取异常，请稍后重试。";
        }
    }

    /**
     * 供下载接口复用的沙箱路径解析。<b>这是本类唯一的对外安全出口</b>，
     * 下载 Controller 必须调用它，禁止另写一套路径拼接。
     *
     * <p>为什么不复用 {@link #readFile}：读取接口面向模型，会截断、加行号、给中文提示；
     * 下载需要原始字节流。但两者的<b>安全边界必须完全一致</b>，故共用 {@link WorkspaceSandbox#resolveInside}，
     * 确保「能读到的文件才能下载」，反之亦然。
     *
     * <p>与工具方法的区别在于会话标识来源：工具从引擎的 {@link RuntimeContext} 拿，
     * 下载从 HTTP 的 sessionId 拿。归一规则仍走同一份 {@link WorkspaceSandbox#normalizeSessionKey(String)}，
     * 因此 {@code trial-12-agent-3-987} 与 {@code trial-12} 指向同一个会话目录。
     *
     * @param sessionId 试跑会话主键（前端传来的数字串）
     * @param fileName  相对文件名，可带 {@code /workspace/} 前缀
     * @return 已通过 canonicalPath 前缀校验的文件路径，调用方须再确认其是普通文件
     * @throws WorkspaceAccessException 工作区不可用、文件名非法或路径越界
     */
    public Path resolveForDownload(String sessionId, String fileName) {
        // 与工具侧同一条闸门：绝对路径、盘符、.. 与符号链接越界都在沙箱里被拦下
        return sandbox.resolveInside(sandbox.sessionRoot("trial-" + sessionId), fileName);
    }

    /**
     * 从引擎上下文取 sessionId；缺失时交给沙箱拒绝，不在这里造占位值。
     *
     * @param runtimeContext 引擎上下文，可空
     * @return 原始 sessionId，可能为 null
     */
    private static String sessionId(RuntimeContext runtimeContext) {
        return runtimeContext == null ? null : runtimeContext.getSessionId();
    }

    /**
     * @param bytes 字节数
     * @return 供人阅读的大小文本
     */
    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024.0) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        return String.format(Locale.ROOT, "%.1f MB", kb / 1024.0);
    }
}
