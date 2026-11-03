package com.agenthub.runtime.infra.agentscope.builtin;

import com.agenthub.runtime.domain.service.WorkspaceAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 会话工作区沙箱。所属域：runtime 的 infra。
 * <p>
 * <b>为什么把它单独抽出来</b>：写文件的地方不止一个——模型主动调 {@code workspaceWriteFile}，
 * 以及运行时把超长工具结果自动落盘（句柄化）。两处如果各写一套路径校验，
 * 必然出现「工具侧禁了脚本后缀、落盘侧忘了禁」这类洞。本类是所有落盘/读取路径的唯一闸门。
 * <p>
 * <b>安全边界（硬性，改代码前先读）：</b>
 * <ol>
 *   <li>沙箱根 = 工作区根目录 + {@code /<sessionKey>}，只能写这里。根目录取
 *       {@code agenthub.workspace.root-path}：默认 {@code ./workspace-test}（项目内，相对进程工作目录），
 *       留空时用 {@code java.io.tmpdir/agenthub_workspace}；</li>
 *   <li>文件名只接受相对路径，拒绝绝对路径、盘符、{@code ..} 段；最终再做一次 canonicalPath
 *       前缀校验，符号链接指到沙箱外也会被这一步挡下；</li>
 *   <li>禁止写入脚本/可执行后缀。</li>
 * </ol>
 * <p>
 * 会话标识由引擎的 {@code RuntimeContext} 给出，<b>不</b>作为工具参数暴露，模型无法指定别人的目录。
 * <p>
 * 线程安全：根路径在构造时固定，其余方法都是无状态纯计算 + 文件系统调用，可并发使用。
 */
@Component
public class WorkspaceSandbox {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSandbox.class);

    /** 对模型展示的虚拟路径前缀；真实路径永远不出现，避免模型学到本机绝对路径 */
    public static final String VIRTUAL_PREFIX = "/workspace/";
    /** 默认沙箱父目录名，留空 {@code agenthub.workspace.root-path} 时挂在系统临时目录下 */
    private static final String DEFAULT_SANDBOX_DIR = "agenthub_workspace";
    /** 文件名字符上限（含子目录），过长的名字只会是攻击面，不是真实用途 */
    private static final int MAX_NAME_LENGTH = 160;
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
    @org.springframework.beans.factory.annotation.Autowired
    public WorkspaceSandbox(@Value("${agenthub.workspace.root-path:}") String rootPath) {
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
     * @return true 表示工作区根可用；false 时所有读写都应被拒绝
     */
    public boolean available() {
        return workspaceRoot != null;
    }

    /**
     * 计算并创建当前会话的沙箱根目录。
     *
     * @param rawSessionId 引擎注入的 sessionId
     * @return 已规范化（解析掉符号链接）的沙箱根路径
     * @throws WorkspaceAccessException 工作区根不可用、会话标识非法或目录无法创建
     */
    public Path sessionRoot(String rawSessionId) {
        if (workspaceRoot == null) {
            throw new WorkspaceAccessException("工作区尚未配置可用目录，无法读写文件。");
        }
        Path root = workspaceRoot.resolve(sessionKey(rawSessionId));
        try {
            Files.createDirectories(root);
            // toRealPath 会把符号链接和 8.3 短名解析成真实路径，后续前缀校验以它为准才可信
            return root.toRealPath();
        } catch (InvalidPathException e) {
            throw new WorkspaceAccessException("工作区路径不合法，无法创建。");
        } catch (IOException e) {
            log.warn("[Agent][workspace] 创建沙箱目录失败 err={}", e.getMessage(), e);
            throw new WorkspaceAccessException("工作区目录创建失败，请稍后再试。");
        }
    }

    /**
     * 把模型给的相对文件名解析成沙箱内的真实路径，任何越界尝试直接拒绝。
     *
     * @param root     会话沙箱根（{@link #sessionRoot(String)} 的返回）
     * @param fileName 相对文件名
     * @return 落在 root 之内的规范化绝对路径
     * @throws WorkspaceAccessException 名字为空、过长为绝对路径、含 .. 或规范化后跑出沙箱
     */
    public Path resolveInside(Path root, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new WorkspaceAccessException("文件名不能为空。");
        }
        // 统一分隔符后再判：模型可能按 Windows 习惯写反斜杠
        String raw = stripVirtualPrefix(fileName.trim().replace('\\', '/'));
        if (raw.isEmpty()) {
            throw new WorkspaceAccessException("文件名不能为空。");
        }
        if (raw.length() > MAX_NAME_LENGTH) {
            throw new WorkspaceAccessException("文件名过长（超过 " + MAX_NAME_LENGTH + " 个字符）。");
        }
        // 冒号在 Windows 上是盘符与 NTFS 备用数据流的入口，一律不收
        if (raw.indexOf(':') >= 0) {
            throw new WorkspaceAccessException("文件名不能包含冒号，只允许 /workspace/ 下的相对路径。");
        }
        // 显式拒绝前导斜杠：Windows 上 "/etc/passwd" 不算绝对路径会被静默当成沙箱内相对路径，
        // 语义上却是模型在尝试写系统目录，必须拒绝而不是悄悄改写成沙箱内的同名文件
        if (raw.startsWith("/")) {
            throw new WorkspaceAccessException("只允许 /workspace/ 下的相对文件名，不要以 / 开头。");
        }
        for (String segment : raw.split("/")) {
            if ("..".equals(segment)) {
                throw new WorkspaceAccessException("文件名不能包含 ..，禁止跨越工作目录。");
            }
        }

        Path parsed;
        try {
            parsed = Path.of(raw);
        } catch (InvalidPathException e) {
            throw new WorkspaceAccessException("文件名包含非法字符。");
        }
        if (parsed.isAbsolute()) {
            throw new WorkspaceAccessException("只允许 /workspace/ 下的相对文件名，不接受绝对路径。");
        }

        File canonical;
        try {
            // 从已规范化的 root 派生，保证前缀比较两侧大小写与真实路径一致；getCanonicalFile 会解析链接
            canonical = new File(root.toFile(), raw).getCanonicalFile();
        } catch (IOException e) {
            throw new WorkspaceAccessException("文件名无法解析，请换一个更简单的名字。");
        }
        Path target = canonical.toPath();
        // 最终一道闸：规范化后必须仍在沙箱内，且不能就是根目录本身（符号链接越狱会在这里被拦下）
        if (target.equals(root) || !target.startsWith(root)) {
            throw new WorkspaceAccessException("路径越界：只允许读写 " + VIRTUAL_PREFIX + " 下的文件。");
        }
        return target;
    }

    /**
     * 拒绝脚本与可执行后缀。
     *
     * @param fileName 已规范化的文件名（只取末段）
     * @throws WorkspaceAccessException 命中黑名单
     */
    public void rejectExecutable(String fileName) {
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
            throw new WorkspaceAccessException(
                    "禁止写入可执行或脚本文件（" + suffix + "），请改用 .md / .txt / .json 等文本后缀。");
        }
    }

    /**
     * @param root   沙箱根
     * @param target 已校验的目标路径
     * @return 相对根的统一斜杠路径，用于拼虚拟路径
     */
    public static String relativeOf(Path root, Path target) {
        return root.relativize(target).toString().replace(File.separatorChar, '/');
    }

    /**
     * @param rawSessionId 引擎注入的 sessionId
     * @return 可用作单级目录名的会话键
     * @throws WorkspaceAccessException 取不到 sessionId 或归一后为空
     */
    public String sessionKey(String rawSessionId) {
        if (rawSessionId == null || rawSessionId.isBlank()) {
            throw new WorkspaceAccessException("当前会话缺少 sessionId，无法定位 /workspace/ 目录。");
        }
        return normalizeSessionKey(rawSessionId.trim());
    }

    /**
     * 会话标识 → 单级目录名。工具与下载接口共用这一份规则，
     * 保证「写文件时用的目录」和「下载时找的目录」永远是同一个。
     *
     * @param trimmed 已 trim 的会话标识
     * @return 可用作单级目录名的会话键
     * @throws WorkspaceAccessException 归一后为空
     */
    public static String normalizeSessionKey(String trimmed) {
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
     * 剥掉模型可能原样回传的虚拟前缀。
     * <p>
     * 写入结果里给的是 {@code /workspace/a.md}，模型下一轮很可能把这个字符串直接当 fileName 传回来；
     * 若直接拒绝前导斜杠会形成「写成功→读失败→再写」的死循环。只剥<b>整段</b> {@code /workspace/}
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
}
