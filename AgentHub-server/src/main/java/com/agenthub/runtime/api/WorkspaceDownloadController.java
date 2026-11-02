package com.agenthub.runtime.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.runtime.application.WorkspaceDownloadAppService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Agent 产物下载。所属域：runtime。
 *
 * <p>身份是平台登录用户：Sa-Token 拦截器覆盖 {@code /api/**}，
 * 再叠加 {@code runtime:trial:list} 权限码，与会话查询/事件回放同级别——
 * 能看到会话的人才能下载它的产物，权限口径不另起一套。
 *
 * <p>安全边界（硬性）：路径解析<b>必须</b>走
 * {@link com.agenthub.runtime.infra.agentscope.builtin.WorkspaceFileTool#resolveForDownload}，
 * 与写文件共用同一份 canonicalPath 前缀校验。禁止在本类里自己拼目录或做 startsWith 判断，
 * 否则符号链接、Windows 8.3 短名、盘符等绕过路径会重新出现。
 */
@RestController
@RequestMapping("/api/runtime/workspace")
public class WorkspaceDownloadController {

    private final WorkspaceDownloadAppService downloads;

    /**
     * @param downloads 归属校验 + 沙箱解析 + 流式写出
     */
    public WorkspaceDownloadController(WorkspaceDownloadAppService downloads) {
        this.downloads = downloads;
    }

    /**
     * 下载本会话工作区里的一个文件。
     *
     * <p>为什么返回 void 而不是 {@code Result<T>}：响应体就是文件字节流，
     * 套一层 JSON 包等于让浏览器下载到一段 Base64 或一个错误对象。
     * 失败时仍由全局异常处理器返回标准 {@code Result}，
     * 但那时代码已抛异常、响应体尚未写出，不会产生半截文件。
     *
     * @param sessionId 会话主键；必须属于当前登录用户
     * @param fileName  相对工作区根的文件名，可带子目录
     * @param response  直接写字节流
     * @throws IOException 写出中断
     */
    @SaCheckPermission(RuntimePermissions.LIST)
    @GetMapping("/download")
    public void download(@RequestParam Long sessionId,
                         @RequestParam String fileName,
                         HttpServletResponse response) throws IOException {
        WorkspaceDownloadAppService.Downloadable file = downloads.resolve(sessionId, fileName);
        // 二进制流：不声明具体类型，避免浏览器把 .md / .json 当页面渲染
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        // attachment 强制下载；filename* 带 UTF-8 编码，中文文件名才不会乱码（RFC 5987）。
        // 同时保留 ASCII 的 filename 兜底，兼容不认 filename* 的老客户端。
        String encoded = URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        // 产物是生成结果而非公共资源：禁止中间层缓存，避免拿到上一个用户的同名文件
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        // 流式写出：不在本层做任何业务判断，路径安全性已由 AppService 保证
        downloads.write(file, response.getOutputStream());
    }
}
