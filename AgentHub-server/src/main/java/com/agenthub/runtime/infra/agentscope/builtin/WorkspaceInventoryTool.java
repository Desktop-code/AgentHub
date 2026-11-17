package com.agenthub.runtime.infra.agentscope.builtin;

import com.agenthub.runtime.domain.service.WorkspaceAccessException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 操作空间目录清单（{@code workspaceInventory}）。所属域：runtime 的 infra。
 *
 * <p>与 {@code workspaceListFiles} 的分工：那个只报「有哪些文件、多大」，用于模型决定读哪个；
 * 本工具额外给出<b>修改时间</b>与稳定排序，用于「清单专家」把操作空间现状写成一份可交付的清单。
 * 两者共用同一个沙箱，不新建目录概念。
 *
 * <p>为什么不改造 {@code workspaceListFiles}：它的返回文案被模型的大量既有提示词引用，
 * 加列会改变既有行为；新增一个语义更窄的清单工具代价更小，也更符合「按现有定义方式落地」。
 *
 * <p>线程安全：无状态，可并发调用。
 */
@Component
public class WorkspaceInventoryTool {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceInventoryTool.class);

    /** 清单最多列出的条目数 */
    private static final int MAX_ENTRIES = 500;
    /** 递归深度：与 workspaceListFiles 保持一致，避免两处口径不同 */
    private static final int MAX_DEPTH = 4;

    /** 修改时间按本地时区展示，与前端时间口径一致（Asia/Shanghai） */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final WorkspaceFileTool files;

    /**
     * @param files 会话工作区文件工具
     */
    public WorkspaceInventoryTool(WorkspaceFileTool files) {
        this.files = files;
    }

    /**
     * 列出当前会话操作空间里的文件、大小与修改时间。
     *
     * @param sortBy 排序方式：name（默认）/ time（修改时间新到旧）/ size（大到小）
     * @param context 引擎注入的会话上下文
     * @return 中文清单；失败时以「列清单失败：」开头
     */
    @Tool(
            name = "workspaceInventory",
            description = "列出当前会话操作空间（/workspace/）里的文件和子目录，含文件大小与修改时间，用于生成目录清单。"
                    + "sortBy 可选 name（默认）/ time / size。只列文件，不跟随符号链接。",
            readOnly = true)
    public String inventory(
            @ToolParam(name = "sortBy", description = "排序：name（按路径，默认）/ time（修改时间新到旧）/ size（大到小）", required = false)
            String sortBy,
            RuntimeContext context
    ) {
        String mode = sortBy == null || sortBy.isBlank() ? "name" : sortBy.trim().toLowerCase(Locale.ROOT);
        if (!"name".equals(mode) && !"time".equals(mode) && !"size".equals(mode)) {
            return "列清单失败：sortBy 只能是 name / time / size。";
        }
        try {
            Path root = files.sandbox().sessionRoot(context == null ? null : context.getSessionId());
            List<Item> items = new ArrayList<>();
            boolean overflow = false;
            try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
                List<Path> all = walk.filter(Files::isRegularFile).toList();
                for (Path path : all) {
                    if (items.size() >= MAX_ENTRIES) {
                        overflow = true;
                        break;
                    }
                    items.add(toItem(root, path));
                }
            }
            sort(items, mode);
            return render(items, mode, overflow);
        } catch (WorkspaceAccessException rejected) {
            return "列清单失败：" + rejected.getMessage();
        } catch (IOException e) {
            log.warn("[Agent][workspaceInventory] 列目录失败 err={}", e.getMessage(), e);
            return "列清单失败：操作空间目录读取异常，请稍后重试。";
        }
    }

    /**
     * 收集单个文件的相对路径、大小与修改时间。
     *
     * @param root 会话工作区根
     * @param path 文件
     * @return 清单条目
     */
    private static Item toItem(Path root, Path path) {
        long size = -1L;
        String time = "未知";
        try {
            size = Files.size(path);
            FileTime modified = Files.getLastModifiedTime(path);
            Instant instant = modified == null ? null : modified.toInstant();
            if (instant != null) {
                time = TIME.format(instant);
            }
        } catch (IOException ignored) {
            // 单个文件取属性失败不影响整份清单，用占位说明
        }
        return new Item(WorkspaceSandbox.relativeOf(root, path), size, time);
    }

    /**
     * 按指定方式排序；name 模式直接按相对路径字典序。
     *
     * @param items 条目
     * @param mode  排序方式
     */
    private static void sort(List<Item> items, String mode) {
        Comparator<Item> comparator = switch (mode) {
            case "time" -> Comparator.comparing((Item item) -> item.time).reversed();
            case "size" -> Comparator.comparingLong((Item item) -> item.size).reversed();
            default -> Comparator.comparing(item -> item.path.toLowerCase(Locale.ROOT));
        };
        items.sort(comparator.thenComparing(item -> item.path));
    }

    /**
     * 渲染成可直接抄进 Markdown 的清单。
     *
     * @param items    条目
     * @param mode     排序方式（回显用）
     * @param overflow 是否因为条目过多被截断
     * @return 清单正文
     */
    private static String render(List<Item> items, String mode, boolean overflow) {
        if (items.isEmpty()) {
            return "当前会话操作空间还没有任何文件。可以先把素材写进 /workspace/，再回来列清单。";
        }
        long totalBytes = items.stream().mapToLong(item -> Math.max(0L, item.size)).sum();
        StringBuilder body = new StringBuilder();
        body.append("操作空间清单：共 ").append(items.size()).append(" 个文件，合计 ")
                .append(WorkshopFiles.num(totalBytes / 1024.0)).append(" KB（排序：").append(mode).append("）\n\n");
        body.append("| 文件 | 大小 | 修改时间 |\n|---|---|---|\n");
        for (Item item : items) {
            body.append("| /workspace/").append(item.path).append(" | ")
                    .append(item.size < 0 ? "未知" : item.size + " B").append(" | ")
                    .append(item.time).append(" |\n");
        }
        if (overflow) {
            body.append("\n…（文件过多，仅列出前 ").append(MAX_ENTRIES).append(" 个）");
        }
        return body.toString().trim();
    }

    /**
     * 一条清单记录。
     *
     * @param path 相对 /workspace/ 的路径
     * @param size 字节数；取不到为 -1
     * @param time 修改时间文本；取不到为「未知」
     */
    private record Item(String path, long size, String time) {
    }
}
