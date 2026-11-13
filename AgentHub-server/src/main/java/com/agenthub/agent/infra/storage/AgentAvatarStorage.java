package com.agenthub.agent.infra.storage;

import com.agenthub.agent.domain.service.AgentLookPalette;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Agent 自定义形象落盘。所属域：agent 的 infra。
 * <p>
 * 根目录取 {@code agenthub.agent-avatar.storage.dir}，默认 {@code ./data/agent-avatars}。
 * 与用户头像目录、URL 前缀都分开：Agent 形象永远不读 {@code sys_user.avatar_url}。
 * 路径只由上传人 userId + UUID 拼出，原始文件名仅取扩展名，杜绝路径穿越。
 */
@Component
public class AgentAvatarStorage {

    private static final Logger log = LoggerFactory.getLogger(AgentAvatarStorage.class);

    /** 允许的图片扩展名（小写） */
    public static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "webp");

    /** 体积上限：2MB */
    public static final long MAX_BYTES = 2L * 1024 * 1024;

    private final Path root;

    /**
     * @param dir 根目录，相对路径按进程工作目录解析
     */
    public AgentAvatarStorage(@Value("${agenthub.agent-avatar.storage.dir:./data/agent-avatars}") String dir) {
        this.root = Paths.get(dir == null || dir.isBlank() ? "./data/agent-avatars" : dir.trim())
                .toAbsolutePath().normalize();
    }

    /**
     * 保存图片字节。
     *
     * @param ownerId  上传人（平台用户 id），仅用于分目录
     * @param fileName 原始文件名，仅取扩展名
     * @param bytes    内容
     * @return 对外相对 URL（以 /uploads/agent-avatars/ 开头）
     * @throws IllegalArgumentException 类型 / 体积不合法
     */
    public String save(Long ownerId, String fileName, byte[] bytes) {
        String ext = extensionOf(fileName);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new IllegalArgumentException("仅支持 jpg / png / webp 图片");
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("文件不能为空");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("形象图片不能超过 2MB");
        }
        String relative = ownerId + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalStateException("拒绝写到 Agent 形象目录之外");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return AgentLookPalette.AVATAR_URL_PREFIX
                    + relative.replace('\\', '/');
        } catch (IOException e) {
            log.warn("[agent-avatar] 写入失败 reason={}", e.getClass().getSimpleName());
            throw new IllegalStateException("保存 Agent 形象失败：" + e.getClass().getSimpleName());
        }
    }

    /**
     * @return 落盘根目录，供静态资源映射
     */
    public Path root() {
        return root;
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
