package com.agenthub.runtime.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.runtime.domain.service.WorkspaceAccessException;
import com.agenthub.runtime.infra.agentscope.builtin.WorkspaceFileTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 工作区文件下载。所属域：runtime。
 *
 * <p>安全职责全部委托给 {@link WorkspaceFileTool#resolveForDownload}：
 * 沙箱根、会话目录归一、路径穿越与符号链接越界校验都在那里，
 * 本类只做「归属确认 + 落地为字节流」，<b>不</b>自己拼任何路径。
 *
 * <p>为什么归属校验单独做：{@code resolveForDownload} 只能保证文件落在<b>某个</b>会话沙箱内，
 * 挡不住「拿着别人的 sessionId 来下载」。因此先用 {@link TrialAccess} 确认该会话属于当前登录用户，
 * 再做路径解析——顺序不能反，否则等于给未授权请求开放了目录探测能力。
 */
@Service
public class WorkspaceDownloadAppService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceDownloadAppService.class);

    private final WorkspaceFileTool files;
    private final TrialAccess access;

    /**
     * @param files  沙箱解析与文件系统访问
     * @param access 会话归属校验（只认当前登录用户）
     */
    public WorkspaceDownloadAppService(WorkspaceFileTool files, TrialAccess access) {
        this.files = files;
        this.access = access;
    }

    /**
     * 解析出可下载的文件。
     *
     * @param sessionId 会话主键
     * @param fileName  相对文件名，可带 /workspace/ 前缀
     * @return 已校验的文件描述（路径 + 展示用文件名）
     * @throws BizException 会话不属于当前用户、文件名非法、路径越界或文件不存在
     */
    public Downloadable resolve(Long sessionId, String fileName) {
        // 先确认会话归属：越权请求不应该走到任何文件系统操作
        access.require(sessionId);
        Path path;
        try {
            path = files.resolveForDownload(String.valueOf(sessionId), fileName);
        } catch (WorkspaceAccessException ex) {
            // 拒绝原因已是人话，且不含绝对路径；按参数错误返回，不暴露内部目录结构
            throw new BizException(ErrorCode.BAD_REQUEST, ex.getMessage());
        }
        if (!Files.isRegularFile(path)) {
            // 区分「是目录」：多半是前端把子目录名当文件名传了，提示比「不存在」更有用
            if (Files.isDirectory(path)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "这是一个目录，无法下载。");
            }
            throw new BizException(ErrorCode.NOT_FOUND, "文件不存在，可能已被清理或尚未生成。");
        }
        if (!Files.isReadable(path)) {
            throw new BizException(ErrorCode.INTERNAL, "文件暂时不可读，请稍后重试。");
        }
        return new Downloadable(path, baseName(fileName));
    }

    /**
     * 把文件流式写进响应。由 Controller 在设好响应头之后调用。
     *
     * @param file     已校验的文件
     * @param response 输出流来源
     * @throws IOException 写出失败（客户端中途断开也会走到这里）
     */
    public void write(Downloadable file, OutputStream response) throws IOException {
        // 用 transferTo 走内核级零拷贝通道，避免把整个文件读进堆内存：
        // 工作区允许 4MB 单文件，多用户并发时一次性 readAllBytes 会明显吃内存
        try (var in = Files.newInputStream(file.path())) {
            in.transferTo(response);
            response.flush();
        } catch (IOException ex) {
            // 客户端取消下载属常见情况，只记一句，不把堆栈刷进日志
            log.warn("[Agent][download] 写出中断 file={} err={}", file.path().getFileName(), ex.getMessage());
            throw ex;
        }
    }

    /**
     * 下载用的文件描述。
     *
     * @param path     已通过沙箱校验的真实路径
     * @param fileName 展示用文件名（只取末段，已去掉任何目录前缀）
     */
    public record Downloadable(Path path, String fileName) {
    }

    /**
     * 从入参里取末段文件名：Content-Disposition 只需要名字，带路径会让浏览器行为不可预期。
     *
     * @param raw 原始入参
     * @return 末段文件名
     */
    private static String baseName(String raw) {
        String normalized = raw == null ? "" : raw.trim().replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        // 末段仍可能被伪装成 . 或 ..：这类名字没有下载价值，直接退回一个安全占位
        return name.isBlank() || ".".equals(name) || "..".equals(name) ? "download" : name;
    }
}
