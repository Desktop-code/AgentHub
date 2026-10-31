package com.agenthub.tool.infra.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;

/**
 * 读取 MCP SSE 帧。所属域：tool 的 infra。
 * 空行结束一帧；以冒号开头的行是注释，丢掉。
 */
final class SseFrames {

    private SseFrames() {
    }

    /**
     * 一帧 SSE。event 缺省按协议视为 message。
     *
     * @param event 事件名
     * @param data  data 行拼起来的正文
     */
    record Frame(String event, String data) {
    }

    /**
     * 读到流结束。出错时投入一帧 error，避免调用方一直等。
     *
     * @param in    SSE 响应体
     * @param queue 帧队列
     */
    static void pump(InputStream in, BlockingQueue<Frame> queue) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String event = "message";
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        queue.offer(new Frame(event, data.toString()));
                    }
                    event = "message";
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    String value = line.substring(5);
                    data.append(value.startsWith(" ") ? value.substring(1) : value);
                }
            }
        } catch (IOException ex) {
            queue.offer(new Frame("error", "SSE 读取中断"));
        }
    }
}
