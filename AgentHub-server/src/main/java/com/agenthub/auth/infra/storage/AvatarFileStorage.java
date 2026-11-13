package com.agenthub.auth.infra.storage;

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
 * 用户头像落盘。所属域：auth 的 infra。
 * <p>
 * 根目录取 {@code agenthub.avatar.storage.dir}，默认 {@code ./data/avatars}（相对进程工作目录）。
 * 路径只由 userId + UUID 拼出，原始文件名仅用于取扩展名，杜绝路径穿越。
 * 对外 URL 前缀固定为 {@code /uploads/avatars/}，由静态资源映射对外；不进 git。
 */
@Component
public class AvatarFileStorage {

    private static final Logger log = LoggerFactory.getLogger(AvatarFileStorage.class);

    /** 允许的图片扩展名（小写） */
    public static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "webp");

    /** 体积上限：2MB */
    public static final long MAX_BYTES = 2L * 1024 * 1024;

    /** 对外访问前缀，与 WebMvc 静态映射一致 */
    public static final String URL_PREFIX = "/uploads/avatars/";

    private final Path root;

    /**
     * @param dir 根目录，相对路径按进程工作目录解析
     */
    public AvatarFileStorage(@Value("${agenthub.avatar.storage.dir:./data/avatars}") String dir) {
        this.root = Paths.get(dir == null || dir.isBlank() ? "./data/avatars" : dir.trim())
                .toAbsolutePath().normalize();
    }

    /**
     * 保存头像字节。
     *
     * @param userId   用户主键
     * @param fileName 原始文件名，仅取扩展名
     * @param bytes    内容
     * @return 对外相对 URL（以 /uploads/avatars/ 开头）
     * @throws IllegalStateException 写盘失败
     */
    public String save(Long userId, String fileName, byte[] bytes) {
        String ext = extensionOf(fileName);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new IllegalArgumentException("仅支持 jpg / png / webp 图片");
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("文件不能为空");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("头像不能超过 2MB");
        }
        String relative = userId + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalStateException("拒绝写到头像目录之外");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return URL_PREFIX + relative.replace('\\', '/');
        } catch (IOException e) {
            throw new IllegalStateException("保存头像失败：" + e.getClass().getSimpleName());
        }
    }

    /**
     * 删除旧头像文件。URL 不属于本存储前缀时跳过（防误删）。
     *
     * @param avatarUrl 库里的相对 URL
     */
    public void deleteByUrl(String avatarUrl) {
        if (avatarUrl == null || avatarUrl.isBlank() || !avatarUrl.startsWith(URL_PREFIX)) {
            return;
        }
        String relative = avatarUrl.substring(URL_PREFIX.length());
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            return;
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("[avatar] 删除旧头像失败 path={} reason={}", relative, e.getClass().getSimpleName());
        }
    }

    /**
     * @return 落盘根目录绝对路径，供静态资源映射
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
