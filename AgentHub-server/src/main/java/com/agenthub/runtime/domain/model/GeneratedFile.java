package com.agenthub.runtime.domain.model;

/**
 * Agent 本轮在工作区生成的一个文件。所属域：runtime。
 *
 * <p>为什么单独建模而不塞进 {@link TrialTrace}：工具结果属于「过程」，
 * 会被截断、会进模型上下文；而产物需要原样保留文件名与体积，
 * 供前端渲染下载卡片、刷新后从事件回放，二者生命周期不同。
 *
 * <p>为什么带参与者身份：产物在方案 / 多专家并行时可能由不同的人写出。
 * 只按文件名归属会让「A 写的文件」先挂到工作流卡、再改挂到 B，页面表现为
 * 「文字 ↔ 文件卡」来回切。归属一旦在产生处确定，前端只按身份渲染，不再重算宿主。
 *
 * @param fileName        相对工作区根的文件名，可含子目录（如 notes/report.md）；不含绝对路径
 * @param size            生成时的文件字节数；&lt; 0 表示取不到（仍可下载）
 * @param participantId   写出该文件的参与者 id；旧数据 / 工具标记产生的文件为空串
 * @param participantName 写出该文件的参与者展示名；与 {@code participantId} 配套
 */
public record GeneratedFile(String fileName, long size, String participantId, String participantName) {

    /**
     * 兼容工具标记（标记里没有参与者身份，只带文件名与体积）。
     *
     * @param fileName 相对工作区根的文件名
     * @param size     字节数
     */
    public GeneratedFile(String fileName, long size) {
        this(fileName, size, "", "");
    }

    public GeneratedFile {
        participantId = participantId == null ? "" : participantId.trim();
        participantName = participantName == null ? "" : participantName.trim();
    }

    /**
     * 补上「谁写的」：已经带身份的不覆盖（内层专家先写，外层方案不得改写）。
     *
     * @param id   参与者 id；空则保持原样
     * @param name 参与者展示名；空则保持原样
     * @return 带身份的文件；已有身份或入参为空时返回自身
     */
    public GeneratedFile withOwner(String id, String name) {
        if (!participantId.isEmpty() || id == null || id.isBlank()) {
            return this;
        }
        return new GeneratedFile(fileName, size, id, name);
    }

    /**
     * @return true 表示这份产物已经知道是谁写的
     */
    public boolean hasOwner() {
        return !participantId.isEmpty();
    }

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
