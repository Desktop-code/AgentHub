package com.agenthub.runtime.infra.storage;

import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.domain.service.UserMessageFiles;
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

/**
 * 对话非图片附件落盘。所属域：runtime 的 infra。
 *
 * <p>和附图同一口径：目录在进程工作目录下，对外 URL 不进 {@code /api/**}，
 * 气泡里的链接才能直接打开。文件名只用会话 id + UUID，原始文件名只留在摘要里。
 * 原文件按字节保存。解析出的正文写在同名 {@code .extract.txt}，只给模型续问时回放，不映射成下载地址。
 */
@Component
public class TrialUserFileStorage {

    /** 对外前缀，与静态资源映射一致 */
    public static final String URL_PREFIX = "/uploads/trial-files/";

    private final Path root;

    /**
     * @param dir 根目录，相对路径按进程工作目录解析
     */
    @Autowired
    public TrialUserFileStorage(
            @Value("${agenthub.trial-file.storage.dir:./data/trial-files}") String dir) {
        this(Paths.get(dir == null || dir.isBlank() ? "./data/trial-files" : dir.trim()));
    }

    /**
     * @param root 已确定的根目录。测试把文件写到临时目录，避免污染本机 data。
     */
    TrialUserFileStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * 按传入顺序把文件写成可回放的地址。
     *
     * @param sessionId 会话主键，只用于分目录
     * @param files     已通过类型校验的 data URI
     * @return 与入参等长的文件引用
     * @throws TrialFailure 解码失败、超过 12MB 或写出失败
     */
    public List<UserMessageFiles.Ref> persist(Long sessionId, List<UserMessageFiles.Draft> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        if (sessionId == null || sessionId < 1) {
            throw new TrialFailure("会话无效，文件无法保存");
        }
        List<UserMessageFiles.Ref> stored = new ArrayList<>(files.size());
        for (UserMessageFiles.Draft file : files) {
            stored.add(persistOne(sessionId, file));
        }
        return List.copyOf(stored);
    }

    /**
     * @return 落盘根目录，供静态资源映射
     */
    public Path root() {
        return root;
    }

    /**
     * 单个文件：按扩展名落盘。体积以解码后的字节为准，不信前端报的大小。
     *
     * @param sessionId 会话主键
     * @param file      已校验的草稿
     * @return 可写进用户事件摘要的引用
     */
    private UserMessageFiles.Ref persistOne(Long sessionId, UserMessageFiles.Draft file) {
        String value = file.dataUrl();
        int marker = value.toLowerCase(Locale.ROOT).indexOf(";base64,");
        if (marker < 0) {
            throw new TrialFailure("文件内容无法识别，请重新选择或粘贴");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(value.substring(marker + ";base64,".length()).replace("\r", "").replace("\n", ""));
        } catch (IllegalArgumentException ex) {
            throw new TrialFailure(file.name() + " 内容无法识别，请重新选择或粘贴");
        }
        if (bytes.length == 0) {
            throw new TrialFailure(file.name() + " 内容为空");
        }
        if (bytes.length > UserMessageFiles.MAX_BYTES) {
            throw new TrialFailure(file.name() + " 超过 12MB，请压缩后再上传");
        }
        String relative = sessionId + "/" + UUID.randomUUID().toString().replace("-", "") + "." + file.extension();
        Path target = root.resolve(relative).normalize();
        // 会话 id 与 UUID 都是我们自己拼的，仍做前缀校验，避免以后改名规则时写出目录
        if (!target.startsWith(root)) {
            throw new TrialFailure("文件保存位置无效");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        } catch (IOException ex) {
            throw new TrialFailure("文件保存失败，请稍后重试");
        }
        return new UserMessageFiles.Ref(file.name(), bytes.length, URL_PREFIX + relative.replace('\\', '/'));
    }

    /**
     * 按对外地址读回文件字节。只接受落在本目录下的相对路径。
     *
     * @param url {@code /uploads/trial-files/...}
     * @return 文件字节
     * @throws TrialFailure 地址越界或文件已不在
     */
    public byte[] readBytes(String url) {
        Path target = resolve(url);
        try {
            return Files.readAllBytes(target);
        } catch (IOException ex) {
            throw new TrialFailure("无法读取该文件");
        }
    }

    /**
     * 把已经交给模型的那一段正文记在文件旁边。再次打开同一会话时直接读它，不再重复认图片页。
     *
     * @param url  文件地址
     * @param text 这一份文件的模型段落，可含失败说明
     */
    public void writeExcerpt(String url, String text) {
        Path target = excerptPath(url);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, text == null ? "" : text, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ex) {
            throw new TrialFailure("无法读取该文件");
        }
    }

    /**
     * 读取先前写下的正文。没有旁路文件时返回空串，调用方按「当时没解析」处理，不抛给用户。
     *
     * @param url 文件地址
     * @return 模型段落；没有或读失败时为空串
     */
    public String readExcerpt(String url) {
        Path target;
        try {
            target = excerptPath(url);
        } catch (RuntimeException ex) {
            return "";
        }
        if (!Files.isRegularFile(target)) {
            return "";
        }
        try {
            return Files.readString(target);
        } catch (IOException ex) {
            return "";
        }
    }

    private Path excerptPath(String url) {
        return Path.of(resolve(url).toString() + ".extract.txt");
    }

    /**
     * 把 URL 收成根目录下的真实路径。拒绝 {@code ..} 和根目录以外的地址。
     *
     * @param url 对外地址
     * @return 规范化后的文件路径
     */
    private Path resolve(String url) {
        if (url == null || !url.startsWith(URL_PREFIX)) {
            throw new TrialFailure("无法读取该文件");
        }
        String relative = url.substring(URL_PREFIX.length());
        if (relative.isBlank() || relative.contains("..")) {
            throw new TrialFailure("无法读取该文件");
        }
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new TrialFailure("无法读取该文件");
        }
        return target;
    }
}
