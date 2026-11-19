package com.agenthub.runtime.infra.storage;

import com.agenthub.runtime.domain.service.TrialFailure;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对话附图落盘。所属域：runtime 的 infra。
 *
 * <p>和头像同一条静态资源口径：目录在进程工作目录下，对外 URL 不进 {@code /api/**}，
 * {@code <img>} 才能直接加载。文件名只用会话 id + UUID，原始文件名不进路径。
 * 粘贴和选文件都先变成 data URI，再从这里落盘；http(s) 地址原样保留，不代为下载。
 */
@Component
public class TrialUserImageStorage {

    /** 对外前缀，与静态资源映射一致 */
    public static final String URL_PREFIX = "/uploads/trial-images/";

    /** 解码后单张上限，与前端约 6MB 对齐 */
    public static final int MAX_BYTES = 6 * 1024 * 1024;

    private static final Pattern DATA_URI = Pattern.compile(
            "^data:(image/(?:png|jpeg|jpg|webp|gif));base64,([A-Za-z0-9+/=\\r\\n]+)$",
            Pattern.CASE_INSENSITIVE);

    private final Path root;

    /**
     * @param dir 根目录，相对路径按进程工作目录解析
     */
    @Autowired
    public TrialUserImageStorage(
            @Value("${agenthub.trial-image.storage.dir:./data/trial-images}") String dir) {
        this(Paths.get(dir == null || dir.isBlank() ? "./data/trial-images" : dir.trim()));
    }

    /**
     * @param root 已确定的根目录。测试把文件写到临时目录，避免污染本机 data。
     */
    TrialUserImageStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * 按传入顺序把图片变成可回放的地址。
     *
     * @param sessionId 会话主键，只用于分目录
     * @param images    已通过张数与体积校验的 data URI 或 http(s) 地址
     * @return 与入参等长的地址列表
     * @throws TrialFailure 格式不对、解码失败或写出失败
     */
    public List<String> persist(Long sessionId, List<String> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        if (sessionId == null || sessionId < 1) {
            throw new TrialFailure("会话无效，图片无法保存");
        }
        List<String> urls = new ArrayList<>(images.size());
        for (String image : images) {
            urls.add(persistOne(sessionId, image));
        }
        return List.copyOf(urls);
    }

    /**
     * @return 落盘根目录，供静态资源映射
     */
    public Path root() {
        return root;
    }

    /**
     * 单张：http(s) 不落盘；data URI 解码后写入会话目录。
     *
     * @param sessionId 会话主键
     * @param raw       一张图片
     * @return 可写进用户事件摘要的地址
     */
    private String persistOne(Long sessionId, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("https://") || value.startsWith("http://")) {
            if (value.length() > 2000) {
                throw new TrialFailure("图片地址过长，请改为上传图片文件");
            }
            return value;
        }
        Matcher matcher = DATA_URI.matcher(value);
        if (!matcher.matches()) {
            throw new TrialFailure("仅支持 png、jpg、webp、gif 图片");
        }
        String mime = matcher.group(1).toLowerCase(Locale.ROOT);
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(matcher.group(2).replace("\r", "").replace("\n", ""));
        } catch (IllegalArgumentException ex) {
            throw new TrialFailure("图片内容无法识别，请重新粘贴或选择文件");
        }
        if (bytes.length == 0) {
            throw new TrialFailure("图片内容为空");
        }
        if (bytes.length > MAX_BYTES) {
            throw new TrialFailure("单张图片过大，请压缩后再上传（单张上限约 6MB）");
        }
        String ext = extensionOf(mime);
        String relative = sessionId + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = root.resolve(relative).normalize();
        // 会话 id 与 UUID 都是我们自己拼的，仍做前缀校验，避免以后改名规则时写出目录
        if (!target.startsWith(root)) {
            throw new TrialFailure("图片保存位置无效");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        } catch (IOException ex) {
            throw new TrialFailure("图片保存失败，请稍后重试");
        }
        return URL_PREFIX + relative.replace('\\', '/');
    }

    /**
     * @param mime data URI 里的 image/*
     * @return 小写扩展名
     */
    private static String extensionOf(String mime) {
        return switch (mime) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> throw new TrialFailure("仅支持 png、jpg、webp、gif 图片");
        };
    }
}
