package com.agenthub.runtime.infra.agentscope.builtin;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import com.agenthub.runtime.domain.service.GeneratedFileMarker;
import com.agenthub.runtime.domain.service.WorkspaceAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 会话级文件读写工具，用于 Context Offloading：长文本落盘，只在对话里留路径。所属域：runtime 的 infra。
 * <p>
 * 三个 {@code @Tool(name)} 须与 tool_item.name 一致：workspaceWriteFile / workspaceReadFile / workspaceListFiles。
 * 同一 Bean 的多个 @Tool 会在注册时被一起挂上（见 {@code BuiltinToolkitAssembler}），
 * 因此绑定其中任意一个即视为启用整套文件能力，种子 SQL 三行应同时启用或同时停用。
 * <p>
 * <b>安全边界（硬性，改代码前先读）：</b>
 * <ol>
 *   <li>沙箱根 = 工作区根目录 + {@code /<sessionKey>}，只能写这里。根目录取 {@code agenthub.workspace.root-path}：
 *       默认 {@code ./workspace-test}（项目内，相对进程工作目录），留空时用 {@code java.io.tmpdir/agenthub_workspace}；</li>
 *   <li>文件名只接受相对路径，拒绝绝对路径、盘符、`..` 段；最终再做一次 canonicalPath 前缀校验，
 *       符号链接指到沙箱外也会被这一步挡下；</li>
 *   <li>禁止写入脚本/可执行后缀；</li>
 *   <li>单次读取最多 {@link #MAX_READ_CHARS} 字符或 {@link #MAX_READ_LINES} 行，超出截断并提示续读。</li>
 * </ol>
 * 会话标识由 {@link RuntimeContext} 隐式注入，<b>不</b>作为工具参数暴露给模型，模型无法指定别人的目录。
 * Hutool 未在本模块引入，统一用 {@code java.nio.file.Files} 实现，避免为一个小工具多引一个依赖。
 */
@Component
public class WorkspaceFileTool {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceFileTool.class);

    /** 对模型展示的虚拟路径前缀；真实路径永远不出现，避免模型学到本机绝对路径 */
    private static final String VIRTUAL_PREFIX = "/workspace/";
    /** 默认沙箱父目录名，留空 {@code agenthub.workspace.root-path} 时挂在系统临时目录下 */
    private static final String DEFAULT_SANDBOX_DIR = "agenthub_workspace";
    /** 单次读取的字符上限：超出即截断，靠 start_line 续读 */
    private static final int MAX_READ_CHARS = 2000;
    /** 单次读取的行数上限 */
    private static final int MAX_READ_LINES = 100;
    /** 单次写入后文件总字节上限，防止模型把整个上下文反复落盘把磁盘写满 */
    private static final long MAX_WRITE_BYTES = 4L * 1024 * 1024;
    /** 文件名字符上限（含子目录），过长的名字只会是攻击面，不是真实用途 */
    private static final int MAX_NAME_LENGTH = 160;
    /** 目录列表最多返回条数，避免一次刷屏挤爆上下文 */
    private static final int MAX_LIST_ENTRIES = 200;
    /** 目录列表递归深度：允许子目录，但禁止深层嵌套 */
    private static final int MAX_LIST_DEPTH = 4;
    /** 会话目录名归一后的最大长度 */
    private static final int MAX_SESSION_KEY_LENGTH = 64;

    /** 禁止落盘的后缀：脚本与可执行体。写进沙箱也不该出现，避免后续被任何环节当程序执行 */
    private static final Set<String> BLOCKED_SUFFIXES = Set.of(
            ".sh", ".bash", ".zsh", ".bat", ".cmd", ".ps1", ".psm1",
            ".exe", ".com", ".scr", ".msi", ".dll", ".sys", ".jar", ".war",
            ".vbs", ".vbe", ".jse", ".wsf", ".wsh", ".hta", ".cpl", ".lnk", ".reg");

    /**
     * 试跑会话的 RuntimeContext.sessionId 形如 {@code trial-12-agent-3-987654321}，尾部的纳秒每轮都变。
     * 沙箱目录必须跟「试跑会话」绑定而不是跟「某一轮」绑定，否则上一轮落盘的文件下一轮就找不到了；
     * 同时抹掉 agentId，让 Router 的子 Agent 与父 Agent 共用同一份会话工作区。
     */
    private static final Pattern TRIAL_SESSION = Pattern.compile("^trial-(\\d+)(?:-agent-\\d+)?(?:-\\d+)?$");

    /**
     * 工作区根目录（绝对化 + 规范化），每个会话在其下建一级子目录。
     * <p>
     * 来源 {@code agenthub.workspace.root-path}：application.yml 默认 {@code ./workspace-test}，
     * 相对路径按进程工作目录解析，本地开发就能把落盘文件放进项目目录；
     * 显式留空 = {@code java.io.tmpdir/agenthub_workspace}（随系统清理）。
     * 为 {@code null} 表示配置值非法、且运行环境又没有临时目录（极少见），
     * 此时所有文件操作按「工作区不可用」拒绝，而不是退到进程工作目录乱写。
     */
    private final Path workspaceRoot;

    /**
     * 解析并固定工作区根目录。只在启动时算一次：路径按进程工作目录解析，
     * 之后即使有人改 user.dir 也不会把不同请求写到两处。
     *
     * @param rootPath {@code agenthub.workspace.root-path} 的原始值，可为空
     */
    public WorkspaceFileTool(@Value("${agenthub.workspace.root-path:}") String rootPath) {
        this.workspaceRoot = resolveWorkspaceRoot(rootPath);
        log.info("[Agent][workspace] 工作区根目录 {}", workspaceRoot == null ? "(不可用)" : workspaceRoot);
    }

    /**
     * 把配置值归一成绝对路径；留空时回落到系统临时目录。
     *
     * @param rootPath 配置值；空白视为未配置
     * @return 绝对规范化路径；配置非法且取不到临时目录时返回 null
     */
    private static Path resolveWorkspaceRoot(String rootPath) {
        if (rootPath != null && !rootPath.isBlank()) {
            try {
                // 相对路径（如 ./workspace-test）按进程工作目录解析，即项目根目录
                return Path.of(rootPath.trim()).toAbsolutePath().normalize();
            } catch (InvalidPathException e) {
                // 配置写错（如含 Windows 非法字符）不该让整个应用起不来，退回临时目录并留警告
                log.warn("[Agent][workspace] 配置的工作区根目录不合法，改用系统临时目录 value={}", rootPath);
            }
        }
        return defaultRoot();
    }

    /**
     * 默认工作区根：系统临时目录下的 {@value #DEFAULT_SANDBOX_DIR}。
     *
     * @return 绝对规范化路径；取不到 java.io.tmpdir 时返回 null
     */
    private static Path defaultRoot() {
        String tmp = System.getProperty("java.io.tmpdir");
        if (tmp == null || tmp.isBlank()) {
            return null;
        }
        return Path.of(tmp, DEFAULT_SANDBOX_DIR).toAbsolutePath().normalize();
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
            Path root = sandboxRoot(runtimeContext);
            Path target = resolveInside(root, fileName);
            // 后缀校验放在规范化之后：先确认路径合法，再看要落盘的到底是什么类型
            rejectExecutable(target.getFileName().toString());

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

            String virtualPath = VIRTUAL_PREFIX + relativeOf(root, target);
            long totalSize = Files.size(target);
            // 首句按约定文案原样返回，附注只补「追加还是覆盖」与当前体积，便于模型判断要不要继续写
            String text = "文件已成功写入。你可以通过路径 " + virtualPath + " 在后续任务中引用它。"
                    + "（" + (appending ? "追加" : "覆盖") + "写入 " + incoming + " 字节，当前共 "
                    + totalSize + " 字节）";
            // 产物标记：运行时会在工具返回给引擎之前物理剔除，模型看不到这段机械字符；
            // 它只是给「SSE / 事件」层识别「本轮生成了哪个文件」用的结构化信号。
            // 体积取写入后的真实字节数，而不是 incoming：追加写入时前者才是可下载的完整大小。
            return text + "\n" + GeneratedFileMarker.mark(relativeOf(root, target), totalSize);
        } catch (Rejected rejected) {
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
                    + "单次最多返回 2000 字符或 100 行，超出会截断并提示用 start_line 继续读。"
                    + "适用于读取之前用 workspaceWriteFile 落盘的长文本。",
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
            Path root = sandboxRoot(runtimeContext);
            Path target = resolveInside(root, fileName);
            if (!Files.isRegularFile(target)) {
                // 区分「不存在」和「是目录」：后者多半是模型把目录名当文件名传了，提示方式不同
                if (Files.isDirectory(target)) {
                    return "读取失败：这是一个目录，不能当文件读取。请用 workspaceListFiles 查看其中的文件。";
                }
                return "读取失败：文件不存在。请先用 workspaceListFiles 确认工作区里有哪些文件。";
            }

            String rel = relativeOf(root, target);
            StringBuilder body = new StringBuilder();
            body.append("文件 ").append(VIRTUAL_PREFIX).append(rel).append(" 的内容（行号 | 正文）：\n");

            int lineNo = 0;
            int emitted = 0;
            int used = 0;
            // resumeAt 非空表示还有没读到的内容，作为下次的 start_line 回给模型
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
        } catch (Rejected rejected) {
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
            Path root = sandboxRoot(runtimeContext);
            // 只列普通文件；不跟随符号链接（walk 默认不跟随），避免把沙箱外的目录带出来
            List<Path> files = new ArrayList<>();
            boolean overflow = false;
            try (Stream<Path> walk = Files.walk(root, MAX_LIST_DEPTH)) {
                List<Path> all = walk.filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(path -> relativeOf(root, path).toLowerCase(Locale.ROOT)))
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
                body.append("- ").append(VIRTUAL_PREFIX).append(relativeOf(root, path))
                        .append("（").append(size < 0 ? "大小未知" : formatBytes(size)).append("）\n");
            }
            if (overflow) {
                body.append("…（文件过多，仅列出前 ").append(MAX_LIST_ENTRIES).append(" 个）");
            }
            return body.toString().trim();
        } catch (Rejected rejected) {
            return "列出文件失败：" + rejected.getMessage();
        } catch (IOException e) {
            log.warn("[Agent][workspaceListFiles] 列目录失败 err={}", e.getMessage(), e);
            return "列出文件失败：工作区目录读取异常，请稍后重试。";
        }
    }

    // ------------------------------------------------------------------
    // 沙箱与路径校验：本类所有文件操作都必须先经过这里，禁止绕过
    // ------------------------------------------------------------------

    /**
     * 供下载接口复用的沙箱路径解析。<b>这是本类唯一的对外安全出口</b>，
     * 下载 Controller 必须调用它，禁止另写一套路径拼接。
     *
     * <p>为什么不复用 {@link #readFile}：读取接口面向模型，会截断、加行号、给中文提示；
     * 下载需要原始字节流。但两者的<b>安全边界必须完全一致</b>，故共用 {@link #resolveInside} 与
     * {@link #sandboxKey}，确保「能读到的文件才能下载」，反之亦然。
     *
     * <p>与工具方法的区别在于会话标识来源：工具从引擎的 {@link RuntimeContext} 拿，
     * 下载从 HTTP 的 sessionId 拿。归一规则仍走同一份 {@link #normalizeSessionKey(String)}，
     * 因此 {@code trial-12-agent-3-987} 与 {@code trial-12} 指向同一个会话目录。
     *
     * @param sessionId 试跑会话主键（前端传来的数字串）
     * @param fileName  相对文件名，可带 {@code /workspace/} 前缀
     * @return 已通过 canonicalPath 前缀校验的文件路径，调用方须再确认其是普通文件
     * @throws WorkspaceAccessException 工作区不可用、文件名非法或路径越界
     */
    public Path resolveForDownload(String sessionId, String fileName) {
        Path root = realRootOf(normalizeSessionKey("trial-" + sessionId));
        try {
            // 与写文件同一条闸门：绝对路径、盘符、.. 与符号链接越界都在这里被拦下
            return resolveInside(root, fileName);
        } catch (Rejected rejected) {
            throw new WorkspaceAccessException(rejected.getMessage());
        }
    }

    /**
     * 按会话键取已规范化的沙箱根，必要时创建。
     *
     * @param sessionKey 已归一的会话目录名
     * @return 解析掉符号链接的真实路径
     * @throws WorkspaceAccessException 工作区根不可用或目录无法创建
     */
    private Path realRootOf(String sessionKey) {
        if (workspaceRoot == null) {
            throw new WorkspaceAccessException("工作区尚未配置可用目录，无法下载文件。");
        }
        Path root = workspaceRoot.resolve(sessionKey);
        try {
            Files.createDirectories(root);
            return root.toRealPath();
        } catch (InvalidPathException e) {
            throw new WorkspaceAccessException("工作区路径不合法。");
        } catch (IOException e) {
            log.warn("[Agent][workspace] 打开会话目录失败 err={}", e.getMessage(), e);
            throw new WorkspaceAccessException("工作区目录不可读，请稍后再试。");
        }
    }

    /**
     * 计算并创建当前会话的沙箱根目录。
     *
     * @param runtimeContext 引擎注入的上下文，从中取 sessionId
     * @return 已规范化（解析掉符号链接）的沙箱根路径
     * @throws Rejected 上下文缺失、工作区根不可用或目录无法创建
     */
    private Path sandboxRoot(RuntimeContext runtimeContext) throws Rejected {
        String sessionKey = sessionKey(runtimeContext);
        // 根目录在启动时固定；拿不到说明既没配 root-path 也没有临时目录，宁可不可用也不写到别处
        if (workspaceRoot == null) {
            throw new Rejected("工作区尚未配置可用目录，无法读写文件。");
        }
        Path root = workspaceRoot.resolve(sessionKey);
        try {
            Files.createDirectories(root);
            // toRealPath 会把符号链接和 8.3 短名解析成真实路径，后续前缀校验以它为准才可信
            return root.toRealPath();
        } catch (InvalidPathException e) {
            throw new Rejected("工作区路径不合法，无法创建。");
        } catch (IOException e) {
            log.warn("[Agent][workspace] 创建沙箱目录失败 err={}", e.getMessage(), e);
            throw new Rejected("工作区目录创建失败，请稍后再试。");
        }
    }

    /**
     * 从引擎上下文推导会话目录名（只做归一，不做业务判断）。
     *
     * @param runtimeContext 引擎注入的上下文；缺失时拒绝，宁可不可用也不跨会话串文件
     * @return 可用作单级目录名的会话键
     * @throws Rejected 取不到 sessionId 或归一后为空
     */
    private static String sessionKey(RuntimeContext runtimeContext) throws Rejected {
        String raw = runtimeContext == null ? null : runtimeContext.getSessionId();
        if (raw == null || raw.isBlank()) {
            throw new Rejected("当前会话缺少 sessionId，无法定位 /workspace/ 目录。");
        }
        try {
            return normalizeSessionKey(raw.trim());
        } catch (WorkspaceAccessException e) {
            // 归一失败对工具侧是「上下文不可用」，转回工具语义，避免异常类型泄漏到引擎
            throw new Rejected(e.getMessage());
        }
    }

    /**
     * 会话标识 → 单级目录名。工具与下载接口共用这一份规则，
     * 保证「写文件时用的目录」和「下载时找的目录」永远是同一个。
     *
     * @param trimmed 已 trim 的会话标识
     * @return 可用作单级目录名的会话键
     * @throws WorkspaceAccessException 归一后为空
     */
    private static String normalizeSessionKey(String trimmed) {
        // 试跑会话的尾部纳秒每轮都变，必须抹掉，否则上一轮落盘的文件下一轮就找不到了
        Matcher trial = TRIAL_SESSION.matcher(trimmed);
        if (trial.matches()) {
            return "trial-" + trial.group(1);
        }
        // 其它来源的 sessionId 一律白名单化：只留字母数字下划线短横，杜绝 . / : 与空白造出路径
        String sanitized = trimmed.replaceAll("[^A-Za-z0-9_-]", "_");
        if (sanitized.isBlank() || "_".equals(sanitized)) {
            throw new WorkspaceAccessException("当前会话标识不可用，无法定位 /workspace/ 目录。");
        }
        return sanitized.length() > MAX_SESSION_KEY_LENGTH
                ? sanitized.substring(0, MAX_SESSION_KEY_LENGTH) : sanitized;
    }

    /**
     * 把模型给的相对文件名解析成沙箱内的真实路径，任何越界尝试直接拒绝。
     *
     * @param root      {@link #sandboxRoot(RuntimeContext)} 返回的规范化根目录
     * @param fileName  模型给的相对文件名
     * @return 落在 root 之内的规范化绝对路径
     * @throws Rejected 名字为空、过长为绝对路径、含 .. 或规范化后跑出沙箱
     */
    private static Path resolveInside(Path root, String fileName) throws Rejected {
        if (fileName == null || fileName.isBlank()) {
            throw new Rejected("文件名不能为空。");
        }
        // 统一分隔符后再判：模型可能按 Windows 习惯写反斜杠
        String raw = stripVirtualPrefix(fileName.trim().replace('\\', '/'));
        if (raw.isEmpty()) {
            throw new Rejected("文件名不能为空。");
        }
        if (raw.length() > MAX_NAME_LENGTH) {
            throw new Rejected("文件名过长（超过 " + MAX_NAME_LENGTH + " 个字符）。");
        }
        // 冒号在 Windows 上是盘符与 NTFS 备用数据流的入口，一律不收
        if (raw.indexOf(':') >= 0) {
            throw new Rejected("文件名不能包含冒号，只允许 /workspace/ 下的相对路径。");
        }
        // 显式拒绝前导斜杠：Windows 上 "/etc/passwd" 不算绝对路径会被静默当成沙箱内相对路径，
        // 语义上却是模型在尝试写系统目录，必须拒绝而不是悄悄改写成沙箱内的同名文件
        if (raw.startsWith("/")) {
            throw new Rejected("只允许 /workspace/ 下的相对文件名，不要以 / 开头。");
        }
        for (String segment : raw.split("/")) {
            if ("..".equals(segment)) {
                throw new Rejected("文件名不能包含 ..，禁止跨越工作目录。");
            }
        }

        Path parsed;
        try {
            parsed = Path.of(raw);
        } catch (InvalidPathException e) {
            throw new Rejected("文件名包含非法字符。");
        }
        if (parsed.isAbsolute()) {
            throw new Rejected("只允许 /workspace/ 下的相对文件名，不接受绝对路径。");
        }

        File canonical;
        try {
            // 从已规范化的 root 派生，保证前缀比较两侧大小写与真实路径一致；getCanonicalFile 会解析链接
            canonical = new File(root.toFile(), raw).getCanonicalFile();
        } catch (IOException e) {
            throw new Rejected("文件名无法解析，请换一个更简单的名字。");
        }
        Path target = canonical.toPath();
        // 最终一道闸：规范化后必须仍在沙箱内，且不能就是根目录本身（符号链接越狱会在这里被拦下）
        if (target.equals(root) || !target.startsWith(root)) {
            throw new Rejected("路径越界：只允许读写 " + VIRTUAL_PREFIX + " 下的文件。");
        }
        return target;
    }

    /**
     * 剥掉模型可能原样回传的虚拟前缀。
     * <p>
     * 写入结果里给的是 {@code /workspace/a.md}，模型下一轮很可能把这个字符串直接当 fileName 传回来；
     * 若直接拒绝前导斜杠会形成「写成功→读失败→再写」的死循环。只剥**整段** {@code /workspace/}
     * （或 {@code workspace/}），剥完仍以 / 开头的一律按越界拒绝，不会放宽其它绝对路径。
     *
     * @param path 已统一为 / 分隔的原始入参
     * @return 去掉虚拟前缀后的相对路径，可能为空串
     */
    private static String stripVirtualPrefix(String path) {
        String value = path;
        if (value.startsWith(VIRTUAL_PREFIX)) {
            value = value.substring(VIRTUAL_PREFIX.length());
        } else if (value.startsWith("workspace/")) {
            // 容忍模型省略前导斜杠的写法
            value = value.substring("workspace/".length());
        }
        // Windows 上 "/" 与 "" 都不算绝对路径，重复前缀（/workspace//workspace/x）剥不完就不放行
        return value;
    }

    /**
     * 拒绝脚本与可执行后缀。
     *
     * @param fileName 已规范化的文件名（只取末段）
     * @throws Rejected 命中黑名单
     */
    private static void rejectExecutable(String fileName) throws Rejected {
        String lower = fileName.toLowerCase(Locale.ROOT);
        // Windows 会忽略结尾的点和空格（a.bat. 等价于 a.bat），先剥掉再判后缀，避免这样绕过
        int end = lower.length();
        while (end > 0 && (lower.charAt(end - 1) == '.' || lower.charAt(end - 1) == ' ')) {
            end--;
        }
        String trimmed = lower.substring(0, end);
        int dot = trimmed.lastIndexOf('.');
        if (dot < 0) {
            return;
        }
        // dot == 0 是 .sh 这类隐藏式命名，后缀同样要拦
        String suffix = "." + trimmed.substring(dot == 0 ? 1 : dot + 1);
        if (BLOCKED_SUFFIXES.contains(suffix)) {
            throw new Rejected("禁止写入可执行或脚本文件（" + suffix + "），请改用 .md / .txt / .json 等文本后缀。");
        }
    }

    /**
     * @param root   沙箱根
     * @param target 已校验的目标路径
     * @return 相对根的统一斜杠路径，用于拼虚拟路径
     */
    private static String relativeOf(Path root, Path target) {
        return root.relativize(target).toString().replace(File.separatorChar, '/');
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

    /**
     * 校验失败的业务异常，message 直接面向模型，禁止塞入本机绝对路径。
     * 关闭堆栈：这类拒绝是预期分支，填堆栈只浪费 CPU。
     */
    private static final class Rejected extends Exception {

        private Rejected(String message) {
            super(message, null, false, false);
        }
    }
}
