package com.agenthub.rag.infra.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * 知识库原文件落盘。所属域：rag 的 infra。
 * <p>
 * 根目录取 {@code agenthub.rag.storage.dir}，默认 {@code ./data/rag}（相对进程工作目录，即项目内），
 * **不新建任何盘符目录**。文件按 kb 分目录、以 docId 命名，避免同名覆盖与中文名带来的路径问题。
 * <p>
 * 安全：路径全部由 kbId / docId 这类数字拼出，用户提供的文件名只用于取扩展名，
 * 因此不存在路径穿越；写入后只回传相对路径，避免把本机绝对路径暴露给前端。
 */
@Component
public class RagFileStorage {

    private static final Logger log = LoggerFactory.getLogger(RagFileStorage.class);

    private final Path root;

    /**
     * @param dir 根目录，相对路径按进程工作目录解析
     */
    public RagFileStorage(@Value("${agenthub.rag.storage.dir:./data/rag}") String dir) {
        this.root = Paths.get(dir == null || dir.isBlank() ? "./data/rag" : dir.trim()).toAbsolutePath().normalize();
    }

    /**
     * 保存原文件字节。
     *
     * @param kbId     知识库
     * @param docId    文档
     * @param fileName 原始文件名，仅用于取扩展名
     * @param bytes    内容
     * @return 相对于根目录的路径，如 {@code 12/345.txt}
     * @throws IllegalStateException 写盘失败
     */
    public String save(Long kbId, Long docId, String fileName, byte[] bytes) {
        String ext = extensionOf(fileName);
        String relative = String.valueOf(kbId) + "/" + docId + (ext.isEmpty() ? "" : "." + ext);
        Path target = root.resolve(relative).normalize();
        // 双保险：解析后必须仍在根目录下，防止拼接逻辑将来被改坏
        if (!target.startsWith(root)) {
            throw new IllegalStateException("拒绝写到知识库落盘目录之外");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes == null ? new byte[0] : bytes,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return relative;
        } catch (IOException e) {
            throw new IllegalStateException("保存原文件失败：" + e.getClass().getSimpleName());
        }
    }

    /**
     * 读取原文件字节。删文档、重灌需要重新解析时使用。
     *
     * @param relativePath {@link #save} 的返回值
     * @return 字节；文件已不存在时返回空数组
     */
    public byte[] read(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return new byte[0];
        }
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            return new byte[0];
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            log.warn("[RAG] 读取原文件失败 path={} reason={}", relativePath, e.getClass().getSimpleName());
            return new byte[0];
        }
    }

    /**
     * 删除原文件。目录留空不递归清，避免误删。
     *
     * @param relativePath {@link #save} 的返回值
     */
    public void delete(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            return;
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            // 删文件失败不影响主流程：库里记录已删，残留文件由运维清理
            log.warn("[RAG] 删除原文件失败 path={} reason={}", relativePath, e.getClass().getSimpleName());
        }
    }

    /**
     * @return 落盘根目录的绝对路径，仅供日志与运维排查
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
        // 只留字母数字，杜绝把路径片段带进文件名
        return fileName.substring(dot + 1).replaceAll("[^A-Za-z0-9]", "");
    }
}
