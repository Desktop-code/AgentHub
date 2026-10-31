package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.PlanStep;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 解析计划模型输出。所属域：runtime，纯逻辑，不依赖引擎与 Spring。
 *
 * 约定模型输出有序 JSON 数组：{@code [{"seq":1,"title":"...","detail":"..."}, ...]}。
 * 容错：允许 markdown 代码块包裹、单对象、seq 缺失或重复（按出现顺序重排）、
 * 以及「只有 title 字段」的退化写法；超出 maxSteps 的部分直接丢弃。
 * 解析不出任何有效步骤时返回空列表，由调用方决定降级（退化为单步 ReAct）。
 */
public final class PlanOutputParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 单步标题上限，超出截断，避免模型把整段正文塞进标题 */
    private static final int TITLE_MAX = 120;

    /** 单步说明上限 */
    private static final int DETAIL_MAX = 600;

    private PlanOutputParser() {
    }

    /**
     * @param reply    模型原文
     * @param maxSteps 步数硬顶；小于 1 时按 1
     * @return 按 seq 从 1 连续编号的步骤；无效或为空时返回空列表
     */
    public static List<PlanStep> parse(String reply, int maxSteps) {
        int cap = Math.max(1, maxSteps);
        Optional<JsonNode> root = extract(reply);
        if (root.isEmpty()) {
            return List.of();
        }
        JsonNode node = root.get();
        List<JsonNode> elements = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(elements::add);
        } else if (node.isObject()) {
            elements.add(node);
        } else {
            return List.of();
        }
        List<PlanStep> steps = new ArrayList<>();
        // seq 一律按出现顺序重排：模型给出的序号重复或跳号都不能影响串行执行顺序
        int seq = 1;
        for (JsonNode element : elements) {
            if (steps.size() >= cap) {
                break;
            }
            if (element == null || !element.isObject()) {
                continue;
            }
            String title = clip(textOf(element, "title"), TITLE_MAX);
            if (title.isEmpty()) {
                // 兼容 "step" / "name" 两种写法；都没有就跳过该元素
                title = clip(textOf(element, "step"), TITLE_MAX);
            }
            if (title.isEmpty()) {
                title = clip(textOf(element, "name"), TITLE_MAX);
            }
            if (title.isEmpty()) {
                continue;
            }
            String detail = clip(textOf(element, "detail"), DETAIL_MAX);
            if (detail.isEmpty()) {
                detail = clip(textOf(element, "description"), DETAIL_MAX);
            }
            steps.add(new PlanStep(seq++, title, detail));
        }
        return steps;
    }

    /**
     * 从可能包着 markdown 代码块或前后废话的文本里取第一个 JSON 数组/对象。
     *
     * @param reply 模型原文，可空
     * @return 解析成功的节点；失败为空
     */
    private static Optional<JsonNode> extract(String reply) {
        if (reply == null || reply.isBlank()) {
            return Optional.empty();
        }
        String text = reply.trim();
        // 容忍 ```json ... ``` 包裹
        if (text.startsWith("```")) {
            int firstNl = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                text = text.substring(firstNl + 1, lastFence).trim();
            }
        }
        int arrayStart = text.indexOf('[');
        int objStart = text.indexOf('{');
        int start;
        char endChar;
        if (arrayStart >= 0 && (objStart < 0 || arrayStart < objStart)) {
            start = arrayStart;
            endChar = ']';
        } else if (objStart >= 0) {
            start = objStart;
            endChar = '}';
        } else {
            return Optional.empty();
        }
        int end = text.lastIndexOf(endChar);
        if (end <= start) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readTree(text.substring(start, end + 1)));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) {
            return "";
        }
        String text = child.asText();
        return text == null ? "" : text.trim();
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.length() <= max) {
            return normalized;
        }
        return normalized.substring(0, max - 1) + "…";
    }
}
