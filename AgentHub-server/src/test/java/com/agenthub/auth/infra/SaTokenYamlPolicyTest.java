package com.agenthub.auth.infra;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核对控制台 Sa-Token 会话策略落在 yml。所属域：auth。
 * 不连库、不起服务：只读 application.yml 中 sa-token 段。
 */
class SaTokenYamlPolicyTest {

    private static final Pattern KEY_VALUE = Pattern.compile("^\\s*([\\w-]+)\\s*:\\s*(.+?)\\s*(?:#.*)?$");

    /**
     * 现网要求：timeout / active-timeout 均为 1200 秒（20 分钟滑动续期窗口）。
     */
    @Test
    void consoleSessionIsTwentyMinutesSliding() throws Exception {
        Path yml = Path.of("src/main/resources/application.yml");
        assertTrue(Files.isRegularFile(yml), "找不到 application.yml");
        Map<String, String> sa = readSaTokenBlock(Files.readString(yml, StandardCharsets.UTF_8));
        assertEquals("1200", sa.get("timeout"), "timeout 应为 1200 秒");
        assertEquals("1200", sa.get("active-timeout"), "active-timeout 应为 1200 秒（滑动续期）");
    }

    /**
     * 截取 sa-token: 段到下一个顶层键，解析简单 k:v（忽略注释）。
     *
     * @param text 完整 yml 文本
     * @return sa-token 下的键值
     */
    private static Map<String, String> readSaTokenBlock(String text) {
        String[] lines = text.split("\\R");
        Map<String, String> out = new LinkedHashMap<>();
        boolean inBlock = false;
        for (String line : lines) {
            if (!inBlock) {
                if (line.matches("^sa-token:\\s*(?:#.*)?$")) {
                    inBlock = true;
                }
                continue;
            }
            // 顶层新键（无缩进）结束本段
            if (!line.isBlank() && !line.startsWith(" ") && !line.startsWith("\t") && !line.trim().startsWith("#")) {
                break;
            }
            Matcher m = KEY_VALUE.matcher(line);
            if (m.matches()) {
                out.put(m.group(1), m.group(2).trim());
            }
        }
        return out;
    }
}
