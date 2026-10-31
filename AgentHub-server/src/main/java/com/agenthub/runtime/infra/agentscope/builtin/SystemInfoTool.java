package com.agenthub.runtime.infra.agentscope.builtin;

import com.agenthub.common.text.UserFacingText;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 本机系统基本信息。@Tool(name) 须与 tool_item.name 一致：systemInfo。
 * 自测可用；开源/默认种子勿默认开放。
 */
@Component
public class SystemInfoTool {

    private static final Logger log = LoggerFactory.getLogger(SystemInfoTool.class);

    private final ObjectMapper objectMapper;

    public SystemInfoTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Tool(
            name = "systemInfo",
            description = "获取当前 Java 应用所在机器的系统基本信息：操作系统、CPU（型号/逻辑核心/负载）、物理内存、磁盘空间、JVM 堆与运行时长。"
                    + "适用于系统监控、性能排查、查询本机配置。无需参数。不查询业务数据库。")
    public String systemInfo() {
        try {
            return objectMapper.writeValueAsString(collectMarkdown());
        } catch (Exception e) {
            // 采集失败多因权限/平台差异，message 里可能带本机路径；用户面只留一句短话
            log.warn("[Agent][systemInfo] 采集失败 err={}", e.getMessage(), e);
            return UserFacingText.SERVICE_UNAVAILABLE;
        }
    }

    static String collectMarkdown() {
        StringBuilder sb = new StringBuilder();
        appendOs(sb);
        appendCpu(sb);
        appendMemory(sb);
        appendJvm(sb);
        appendDisks(sb);
        return sb.toString().trim();
    }

    private static void appendOs(StringBuilder sb) {
        sb.append("## 操作系统\n");
        sb.append("- 名称：").append(nvl(System.getProperty("os.name"))).append('\n');
        sb.append("- 版本：").append(nvl(System.getProperty("os.version"))).append('\n');
        sb.append("- 架构：").append(nvl(System.getProperty("os.arch"))).append('\n');
        sb.append('\n');
    }

    private static void appendCpu(StringBuilder sb) {
        java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        sb.append("## CPU\n");
        sb.append("- 标识：").append(resolveCpuIdentifier()).append('\n');
        sb.append("- 逻辑核心：").append(os.getAvailableProcessors()).append('\n');
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            sb.append("- 系统负载：").append(formatPercent(sampleCpuLoad(sun))).append('\n');
            sb.append("- 本进程负载：").append(formatPercent(sun.getProcessCpuLoad())).append('\n');
        }
        sb.append('\n');
    }

    private static void appendMemory(StringBuilder sb) {
        sb.append("## 物理内存\n");
        java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            long total = sun.getTotalMemorySize();
            long free = sun.getFreeMemorySize();
            long used = Math.max(0L, total - free);
            sb.append("- 总量：").append(formatBytes(total)).append('\n');
            sb.append("- 已用：").append(formatBytes(used)).append('\n');
            sb.append("- 可用：").append(formatBytes(free)).append('\n');
        } else {
            sb.append("- 当前 JDK 未提供物理内存指标\n");
        }
        sb.append('\n');
    }

    private static void appendJvm(StringBuilder sb) {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        sb.append("## JVM\n");
        sb.append("- Java：").append(nvl(System.getProperty("java.version"))).append('\n');
        sb.append("- 虚拟机：").append(nvl(runtime.getVmName())).append(' ').append(nvl(runtime.getVmVersion())).append('\n');
        sb.append("- 堆已用 / 已提交 / 最大：")
                .append(formatBytes(heap.getUsed())).append(" / ")
                .append(formatBytes(heap.getCommitted())).append(" / ")
                .append(formatBytes(heap.getMax())).append('\n');
        sb.append("- 运行时长：").append(formatDuration(runtime.getUptime())).append('\n');
        sb.append('\n');
    }

    private static void appendDisks(StringBuilder sb) {
        sb.append("## 磁盘\n");
        File[] roots = File.listRoots();
        if (roots == null || roots.length == 0) {
            sb.append("- 无可用盘符\n");
            return;
        }
        int shown = 0;
        for (File root : roots) {
            long total = root.getTotalSpace();
            if (total <= 0L) {
                continue;
            }
            long free = root.getUsableSpace();
            long used = Math.max(0L, total - free);
            sb.append("- ").append(root.getAbsolutePath())
                    .append(" 总量 ").append(formatBytes(total))
                    .append("，已用 ").append(formatBytes(used))
                    .append("，可用 ").append(formatBytes(free))
                    .append('\n');
            shown++;
        }
        if (shown == 0) {
            sb.append("- 无可用盘符\n");
        }
    }

    private static double sampleCpuLoad(com.sun.management.OperatingSystemMXBean sun) {
        sun.getCpuLoad();
        try {
            TimeUnit.MILLISECONDS.sleep(200L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return sun.getCpuLoad();
    }

    private static String resolveCpuIdentifier() {
        String win = System.getenv("PROCESSOR_IDENTIFIER");
        if (win != null && !win.isBlank()) {
            return win.trim();
        }
        String linux = readLinuxCpuModel();
        if (linux != null && !linux.isBlank()) {
            return linux;
        }
        return nvl(System.getProperty("os.arch"));
    }

    private static String readLinuxCpuModel() {
        Path cpuInfo = Path.of("/proc/cpuinfo");
        if (!Files.isRegularFile(cpuInfo)) {
            return "";
        }
        try (BufferedReader reader = Files.newBufferedReader(cpuInfo, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.toLowerCase(Locale.ROOT).startsWith("model name")) {
                    int colon = line.indexOf(':');
                    return colon >= 0 ? line.substring(colon + 1).trim() : line.trim();
                }
            }
        } catch (IOException ignore) {
            return "";
        }
        return "";
    }

    static String formatPercent(double load) {
        if (Double.isNaN(load) || load < 0) {
            return "暂不可用";
        }
        return String.format(Locale.ROOT, "%.1f%%", load * 100.0);
    }

    static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "未知";
        }
        double gb = bytes / (1024.0 * 1024.0 * 1024.0);
        if (gb >= 1.0) {
            return String.format(Locale.ROOT, "%.2f GB", gb);
        }
        double mb = bytes / (1024.0 * 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", mb);
    }

    static String formatDuration(long millis) {
        long sec = Math.max(0L, millis / 1000L);
        long hour = sec / 3600L;
        long min = (sec % 3600L) / 60L;
        long s = sec % 60L;
        if (hour > 0) {
            return hour + " 小时 " + min + " 分";
        }
        if (min > 0) {
            return min + " 分 " + s + " 秒";
        }
        return s + " 秒";
    }

    private static String nvl(String v) {
        return v == null || v.isBlank() ? "-" : v.trim();
    }
}
