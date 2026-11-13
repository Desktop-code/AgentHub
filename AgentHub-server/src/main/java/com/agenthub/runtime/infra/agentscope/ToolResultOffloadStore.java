package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.service.CauseDigest;
import com.agenthub.runtime.domain.service.ToolResultHandle;
import com.agenthub.runtime.domain.service.ToolResultOffloadPolicy;
import com.agenthub.runtime.domain.service.WorkspaceAccessException;
import com.agenthub.runtime.infra.agentscope.builtin.WorkspaceSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * 把超长工具结果落到会话工作区，并生成回灌摘要。所属域：runtime 的 infra。
 * <p>
 * 为什么在工具层（{@code FileMarkerAgentTool} 里）做而不是出站时做：
 * 出站时只看到一堆 messages，已经不知道该条结果来自哪个工具、属于哪一轮，
 * 也无法把「哪条结果落到了哪个文件」写回时间线给用户看。
 * 在工具返回那一刻落盘，信息最全：工具名、成败、会话都在手上。
 * <p>
 * 路径与文件名：
 * <ul>
 *   <li>目录固定 {@code tool-results/}，与模型自己写的文件分开，便于 workspaceListFiles 时区分；</li>
 *   <li>文件名含工具名 + 序号 + 时间戳，保证同轮多次调用不互相覆盖；
 *       工具名做白名单化（只留字母数字下划线），防止工具名里的怪字符变成路径。</li>
 * </ul>
 * <p>
 * <b>失败一律降级</b>：工作区不可用、磁盘写失败、会话缺 id——任何一种都只记日志并返回空，
 * 调用方退回原来的字符截断。落盘是优化，不能让一次磁盘问题打断整轮对话。
 * <p>
 * 线程安全：无状态；{@link WorkspaceSandbox} 本身可并发使用。
 */
@Component
public class ToolResultOffloadStore {

    private static final Logger log = LoggerFactory.getLogger(ToolResultOffloadStore.class);

    /** 落盘目录：与模型自写文件区分，便于用户在工作区里一眼认出「这是自动落盘的」 */
    private static final String RESULT_DIR = "tool-results";
    /** 文件名里工具名的最大长度 */
    private static final int MAX_TOOL_NAME_IN_FILE = 48;
    /** 单条落盘正文上限（字符）：超过只落前面这部分，避免磁盘被单次调用写满 */
    private static final int MAX_STORE_CHARS = 400_000;

    private final WorkspaceSandbox sandbox;
    private final TrialProperties properties;

    /**
     * @param sandbox    会话工作区沙箱，与文件工具共用同一个根
     * @param properties 摘要保留长度等参数
     */
    public ToolResultOffloadStore(WorkspaceSandbox sandbox, TrialProperties properties) {
        this.sandbox = sandbox;
        this.properties = properties;
    }

    /**
     * 按策略判断并落盘。
     *
     * <p>调用方必须已经确认当前专家绑定了读文件工具（见
     * {@link ToolResultOffloadPolicy#readToolIn(java.util.Collection)}）：没有读文件工具时
     * 落盘只会留下一个「说了也读不到」的路径，还会把模型逼去调不存在的工具。
     *
     * @param rawSessionId    引擎注入的 sessionId，可空
     * @param toolName        工具调用名，可空
     * @param text            原始结果正文，可空
     * @param success         引擎报告的成败
     * @param offloadMaxChars 落盘阈值；≤0 关闭
     * @param readToolName    当前专家已绑定的读文件工具名；空串表示不写读取指令（防御分支）
     * @return 落盘成功时给出手柄；不需要落盘、工作区不可用或写失败时为空
     */
    public Optional<ToolResultHandle> offload(String rawSessionId, String toolName, String text,
                                              boolean success, int offloadMaxChars, String readToolName) {
        if (!ToolResultOffloadPolicy.shouldOffload(toolName, text, success, offloadMaxChars)) {
            return Optional.empty();
        }
        if (!sandbox.available()) {
            // 没配工作区就退回原行为：落不了盘不该让工具结果变成一条错误
            return Optional.empty();
        }
        try {
            Path root = sandbox.sessionRoot(rawSessionId);
            String fileName = nextFileName(toolName);
            Path target = sandbox.resolveInside(root, fileName);
            // 与模型自写文件同一条闸门：可执行后缀在这里同样被拒（文件名由我们生成，属防御性校验）
            sandbox.rejectExecutable(target.getFileName().toString());
            Files.createDirectories(target.getParent());

            // 超长正文只落前一段：剩下的部分即使落盘也没人会读，但磁盘会被写满
            String body = text.length() > MAX_STORE_CHARS ? text.substring(0, MAX_STORE_CHARS) : text;
            Files.writeString(target, body, StandardCharsets.UTF_8);

            String virtualPath = WorkspaceSandbox.VIRTUAL_PREFIX + WorkspaceSandbox.relativeOf(root, target);
            String preview = ToolResultOffloadPolicy.previewOf(
                    toolName, text, virtualPath, properties.offloadPreviewChars(), readToolName);
            // 只记路径与长度：结果正文可能含业务数据，不进日志
            log.info("[Agent][offload] 工具结果已落盘 tool={} chars={} path={}", toolName, text.length(), virtualPath);
            return Optional.of(new ToolResultHandle(
                    WorkspaceSandbox.relativeOf(root, target), virtualPath, text.length(), preview));
        } catch (WorkspaceAccessException rejected) {
            log.debug("[Agent][offload] 落盘被拒 tool={} reason={}", toolName, rejected.getMessage());
            return Optional.empty();
        } catch (IOException ex) {
            // 磁盘问题不该打断对话：带类型记一次，调用方退回字符截断
            log.warn("[Agent][offload] 落盘失败 tool={} type={} cause={}",
                    toolName, ex.getClass().getSimpleName(), CauseDigest.of(ex));
            return Optional.empty();
        } catch (RuntimeException ex) {
            log.warn("[Agent][offload] 落盘异常 tool={} type={} cause={}",
                    toolName, ex.getClass().getSimpleName(), CauseDigest.of(ex));
            return Optional.empty();
        }
    }

    /**
     * 生成本次落盘的文件名：{@code tool-results/<工具名>-<纳秒>.txt}。
     * <p>
     * 用纳秒而不是自增序号：{@code FileMarkerAgentTool} 在 {@code boundedElastic} 上并发跑，
     * 计数器要额外加锁，而纳秒天然唯一且不共享状态。
     *
     * @param toolName 工具名，可空
     * @return 相对文件名（含 tool-results/ 前缀）
     */
    private static String nextFileName(String toolName) {
        String safe = toolName == null ? "tool" : toolName.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]", "_");
        if (safe.isBlank() || "_".equals(safe)) {
            safe = "tool";
        }
        if (safe.length() > MAX_TOOL_NAME_IN_FILE) {
            safe = safe.substring(0, MAX_TOOL_NAME_IN_FILE);
        }
        return RESULT_DIR + "/" + safe + "-" + System.nanoTime() + ".txt";
    }
}
