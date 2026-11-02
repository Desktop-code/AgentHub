package com.agenthub.runtime.domain.model;

/**
 * Agent 本轮在工作区生成的一个文件。所属域：runtime。
 *
 * <p>为什么单独建模而不塞进 {@link TrialTrace}：工具结果属于「过程」，
 * 会被截断、会进模型上下文；而产物需要原样保留文件名与体积，
 * 供前端渲染下载卡片、刷新后从事件回放，二者生命周期不同。
 *
 * @param fileName 相对工作区根的文件名，可含子目录（如 notes/report.md）；不含绝对路径
 * @param size     生成时的文件字节数；&lt; 0 表示取不到（仍可下载）
 */
public record GeneratedFile(String fileName, long size) {

    /**
     * @return 供人阅读的体积，如 {@code 2.0 KB}；体积未知时给占位说明而不是 0 B
     */
    public String humanSize() {
        if (size < 0) {
            return "大小未知";
        }
        if (size < 1024L) {
            return size + " B";
        }
        double kb = size / 1024.0;
        if (kb < 1024.0) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", kb);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", kb / 1024.0);
    }
}
