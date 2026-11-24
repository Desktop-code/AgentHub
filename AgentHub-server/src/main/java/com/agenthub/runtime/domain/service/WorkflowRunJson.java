package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.WorkflowRunView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流执行卡片视图的 JSON 序列化。所属域：runtime，纯逻辑。
 *
 * <p>形状（字段名即前后端契约，改名属协议变更）：
 * <pre>
 * {"name":"制度要点与报告","code":"policy_brief_report","status":"RUNNING","answer":"",
 *  "nodes":[{"code":"policy_points","name":"知识问答","type":"AGENT",
 *            "status":"RUNNING","note":"执行中",
 *            "output":"节点正文","steps":[{"type":"REASONING","name":"","content":"…","state":""}],
 *            "files":[{"fileName":"game.html","size":1234}],
 *            "reply":"HTML/CSS/JavaScript 单页"}]}
 * </pre>
 *
 * <p>节点内字段只在非空时写入：旧卡片没有它们时前端回落卡级 answer，
 * 新卡片把过程 / 正文 / 文件 / 用户回复挂在对应节点里。
 *
 * <p>两条来源共用本类：实时 SSE {@code workflow} 帧与落库 {@code WORKFLOW} 事件正文。
 * 前端一套解析即可覆盖「实时推送 / 内存快照 / 刷新回放」。
 *
 * <p>线程安全：纯静态方法，只读入参。
 */
public final class WorkflowRunJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 落库骨架的版本号。读侧只补全「带这个版本号」的卡片；
     * 旧卡片（没有该键）保持原样，避免把历史全量卡也当成骨架去重算。
     */
    public static final int STORAGE_VERSION = 2;

    /**
     * 落库骨架的字符硬顶。
     *
     * <p>为什么不等于 {@code content_text} 的 32K：那个上限来自「截断纯文本」的通道，
     * 对 JSON 做字符截断必然产出半截字符串，前端只能整块丢弃。这里在写库前就把骨架压到
     * 远低于 32K，保证<b>永不触发那一次截断</b>；节点产出等大字段本来就不进事件。
     */
    public static final int STORAGE_LIMIT = 16 * 1024;

    /** 每个节点落库保留的过程步数：过程只是辅助信息，卡片默认也不全展开 */
    private static final int STORAGE_STEPS_PER_NODE = 4;

    /** 单条过程步落库的展示硬顶 */
    private static final int STORAGE_STEP_MAX = 400;

    /** 骨架仍超限时，正文逐档压缩；最后一档只留一句摘要 */
    private static final int[] ANSWER_FALLBACKS = {4000, 2000, 800, 300};

    private WorkflowRunJson() {
    }

    /**
     * 转成 SSE 帧载荷（对象形态，出站层再统一序列化一次）。
     *
     * @param view 执行卡片视图；可空
     * @return 载荷对象；视图为空时返回空 Map（调用方据此跳过出站）
     */
    public static Map<String, Object> payload(WorkflowRunView view) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (view == null) {
            return body;
        }
        body.put("name", view.name());
        body.put("code", view.code());
        // 运行主键：落库指针，读侧靠它按 run 现算节点产出；前端不消费，旧卡片没有这个键
        if (view.runId() != null) {
            body.put("runId", view.runId());
        }
        body.put("status", view.status());
        body.put("answer", view.answer());
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (WorkflowRunView.Node node : view.nodes()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("code", node.code());
            one.put("name", node.name());
            one.put("type", node.type());
            one.put("status", node.status());
            one.put("note", node.note());
            // 节点内过程 / 正文 / 文件：只在真的有内容时写，旧卡片回放不因多出空数组而变胖
            if (!node.output().isBlank()) {
                one.put("output", node.output());
            }
            if (!node.steps().isEmpty()) {
                List<Map<String, Object>> steps = new ArrayList<>();
                for (WorkflowRunView.Step step : node.steps()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("type", step.type());
                    item.put("name", step.name());
                    item.put("content", step.content());
                    item.put("state", step.state());
                    steps.add(item);
                }
                one.put("steps", steps);
            }
            if (!node.files().isEmpty()) {
                List<Map<String, Object>> files = new ArrayList<>();
                for (WorkflowRunView.NodeFile file : node.files()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("fileName", file.fileName());
                    item.put("size", file.size());
                    files.add(item);
                }
                one.put("files", files);
            }
            /*
             * 用户对询问的回复：只挂在被答复的询问节点上，非空才写。
             * 这条不落成新的用户消息，是「我答了什么」的唯一展示来源。
             */
            if (!node.reply().isBlank()) {
                one.put("reply", node.reply());
            }
            nodes.add(one);
        }
        body.put("nodes", nodes);
        // 可点选项：只在停等询问时出现；没有就完全不写这个键，老前端无感
        WorkflowRunView.Choice choice = view.choice();
        if (choice != null && choice.usable()) {
            Map<String, Object> pick = new LinkedHashMap<>();
            pick.put("question", choice.question());
            List<Map<String, Object>> options = new ArrayList<>();
            for (WorkflowRunView.Choice.Option option : choice.options()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("id", option.id());
                one.put("label", option.label());
                options.add(one);
            }
            pick.put("options", options);
            body.put("choice", pick);
        }
        /*
         * 运行时限：只在算得出时写这个键，老卡片 / 老前端不受影响。
         * 送绝对截止而不是剩余毫秒——前端每次按同一个截止现算，
         * 刷新、离开再进、回放看到的剩余才不会各自从头计时。
         */
        WorkflowRunView.Limits limits = view.limits();
        if (limits != null) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("runLimitMs", limits.runLimitMs());
            one.put("runDeadlineAt", limits.runDeadlineAt());
            if (limits.waitingAt() != null) {
                one.put("waitingAt", limits.waitingAt());
            }
            if (limits.nodeCode() != null) {
                one.put("nodeCode", limits.nodeCode());
                one.put("nodeName", limits.nodeName() == null ? "" : limits.nodeName());
            }
            if (limits.nodeLimitMs() != null) {
                one.put("nodeLimitMs", limits.nodeLimitMs());
                one.put("nodeDeadlineAt", limits.nodeDeadlineAt());
            }
            body.put("limits", one);
        }
        return body;
    }

    /**
     * 写成完整卡片 JSON（SSE 帧 / 内存快照用，含节点产出）。
     *
     * <p>落库不要用这个：{@link #compact(WorkflowRunView)} 才是不带节点产出的骨架形态。
     *
     * @param view 执行卡片视图；可空
     * @return JSON 字符串；视图为空或序列化失败时返回空串（调用方据此跳过出站）
     */
    public static String write(WorkflowRunView view) {
        if (view == null) {
            return "";
        }
        try {
            return JSON.writeValueAsString(payload(view));
        } catch (JsonProcessingException ex) {
            // 载荷只含字符串，正常不会失败；失败时宁可不落库，也不把异常抛进运行主链路
            return "";
        }
    }

    /**
     * 写成落库骨架（{@code content_text} 用）。
     *
     * <p>与 {@link #write(WorkflowRunView)} 的区别只有一个：<b>不带节点产出</b>。
     * 节点正文的真源是 {@code workflow_node_run.output_json}，事件里再抄一份既浪费列宽，
     * 又会在多节点 / 长代码时把整份 JSON 顶过截断线。刷新回放由服务端按 {@code runId}
     * 现算（同一套节点取文口径），因此这里只要留下卡片骨架与指针。
     *
     * <p>返回值保证是合法 JSON 且不超过 {@link #STORAGE_LIMIT}：超限时依次丢过程步、
     * 压正文、最后只留卡头，绝不返回半截字符串。
     *
     * @param view 执行卡片视图；可空
     * @return 紧凑 JSON；视图为空时为空串
     */
    public static String compact(WorkflowRunView view) {
        if (view == null) {
            return "";
        }
        ObjectNode body = JSON.valueToTree(payload(view));
        body.put("v", STORAGE_VERSION);
        slimNodes(body);
        return fit(body, STORAGE_LIMIT);
    }

    /**
     * 把任意来源的卡片 JSON 收成可安全落库的形态。
     *
     * <p>为什么要这一步：直播帧与内存快照仍走 {@link #write(WorkflowRunView)}（含节点产出），
     * 停止补落库等路径会把它们直接写进事件。这里统一剥掉产出、压缩过程，再套用上限，
     * 避免「实时能看、刷新丢卡」再次发生。
     *
     * <p>已经是骨架（{@code v >= STORAGE_VERSION}）且不超限的文本原样返回，不重新序列化，
     * 避免字段顺序与数值精度被改写。
     *
     * @param raw 卡片 JSON 文本；可空
     * @return 可落库的 JSON；非对象 / 解析失败时为空串（调用方跳过落库，不写半截）
     */
    public static String forStorage(String raw) {
        return forStorage(raw, STORAGE_LIMIT);
    }

    /**
     * 按调用方给定的上限收成可落库形态。
     *
     * <p>上限取 {@code min(configured, STORAGE_LIMIT)}：运维把 {@code content-limit-chars}
     * 调小时，卡片也跟着收，不会出现「配置说 8K、卡片却写 16K」。
     * 非正数按 {@link #STORAGE_LIMIT} 处理。
     *
     * @param raw   卡片 JSON 文本；可空
     * @param limit 期望的字符上限
     * @return 可落库的 JSON；非对象 / 解析失败时为空串
     */
    public static String forStorage(String raw, int limit) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        int max = limit <= 0 ? STORAGE_LIMIT : Math.min(limit, STORAGE_LIMIT);
        String text = raw.trim();
        try {
            JsonNode root = JSON.readTree(text);
            if (!(root instanceof ObjectNode body)) {
                return "";
            }
            if (body.path("v").asInt(0) >= STORAGE_VERSION && text.length() <= max
                    && !hasNodeOutput(body)) {
                return text;
            }
            body.put("v", STORAGE_VERSION);
            slimNodes(body);
            return fit(body, max);
        } catch (JsonProcessingException ex) {
            // 半截 JSON（历史脏数据）：无法安全修好，宁可不写，交给前端出占位卡
            return "";
        }
    }

    /** 是否还有节点的 {@code output}：旧全量卡会带上，需要剥掉后重新压到限内。 */
    private static boolean hasNodeOutput(ObjectNode body) {
        JsonNode nodes = body.get("nodes");
        if (!(nodes instanceof ArrayNode list)) {
            return false;
        }
        for (JsonNode item : list) {
            if (item instanceof ObjectNode node && node.has("output")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 剥掉节点产出、压缩过程步。
     *
     * <p>只动节点内部：卡片头、正文、选项、时限原样保留；用户回复保留（它很小，
     * 且是「我答了什么」的唯一展示来源，库里另有 reply_text 作为持久真源）。
     *
     * @param body 卡片 JSON 对象（原地修改）
     */
    private static void slimNodes(ObjectNode body) {
        JsonNode nodes = body.get("nodes");
        if (!(nodes instanceof ArrayNode list)) {
            return;
        }
        for (JsonNode item : list) {
            if (!(item instanceof ObjectNode node)) {
                continue;
            }
            // 节点产出是真源表里的字段，不进事件正文
            node.remove("output");
            trimSteps(node);
        }
    }

    /** 过程步只留前 N 条、每条截到展示硬顶；超出的过程对回放没有信息增量。 */
    private static void trimSteps(ObjectNode node) {
        JsonNode steps = node.get("steps");
        if (!(steps instanceof ArrayNode list) || list.isEmpty()) {
            node.remove("steps");
            return;
        }
        ArrayNode kept = JSON.createArrayNode();
        for (JsonNode step : list) {
            if (kept.size() >= STORAGE_STEPS_PER_NODE) {
                break;
            }
            if (!(step instanceof ObjectNode one)) {
                continue;
            }
            String content = one.path("content").asText("");
            if (content.length() > STORAGE_STEP_MAX) {
                one.put("content", clipRaw(content, STORAGE_STEP_MAX));
            }
            kept.add(one);
        }
        if (kept.isEmpty()) {
            node.remove("steps");
        } else {
            node.set("steps", kept);
        }
    }

    /**
     * 逐档压缩到给定上限以内。
     *
     * <p>顺序是有意为之：<b>先丢过程、再压正文、最后丢节点表</b>。
     * 过程是可选细节；正文是用户已经看过的字（尽量保留）；节点状态是卡片的结构，
     * 只有在前两者都压到极限仍超限时才放弃（此时读侧仍能按 runId 补回节点）。
     *
     * @param body  卡片 JSON（原地修改）
     * @param limit 字符上限
     * @return 不超过上限的 JSON 文本；序列化失败时为空串
     */
    private static String fit(ObjectNode body, int limit) {
        String text = write(body);
        if (text.isEmpty() || text.length() <= limit) {
            return text;
        }
        // 1) 过程整块丢掉：节点状态 / 正文 / 选项仍在
        if (stripAllSteps(body)) {
            text = write(body);
            if (text.length() <= limit) {
                return text;
            }
        }
        // 2) 正文逐档压缩
        String answer = body.path("answer").asText("");
        for (int cap : ANSWER_FALLBACKS) {
            body.put("answer", clipRaw(answer, cap));
            text = write(body);
            if (text.length() <= limit) {
                return text;
            }
        }
        // 3) 极端兜底：只留卡头，节点表整个不要（读侧仍按 runId 现算）
        body.remove("nodes");
        body.remove("choice");
        body.remove("limits");
        text = write(body);
        if (text.length() <= limit) {
            return text;
        }
        // 4) 理论不可达（卡头只有几个短字段）：留空表示不可安全落库
        return "";
    }

    /** 一次清掉所有节点的过程步；返回是否真的删过。 */
    private static boolean stripAllSteps(ObjectNode body) {
        boolean removed = false;
        JsonNode nodes = body.get("nodes");
        if (nodes instanceof ArrayNode list) {
            for (JsonNode item : list) {
                if (item instanceof ObjectNode node && node.has("steps")) {
                    node.remove("steps");
                    removed = true;
                }
            }
        }
        return removed;
    }

    /** 简单字符硬截（不折叠换行）：用于落库骨架里的正文与过程，追一个省略号。 */
    private static String clipRaw(String text, int max) {
        String value = text == null ? "" : text;
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** 序列化骨架；失败返回空串（调用方据此跳过落库）。 */
    private static String write(ObjectNode body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            return "";
        }
    }

    /**
     * 把一份仍在运行的工作流卡片快照改成「用户已停止」的终态。
     *
     * <p>用途：工作台停止一轮时，内存快照里最后一张卡片通常还写着 RUNNING、
     * 在途节点也还是「执行中」。取消接口已把库里的 run 写成 CANCELLED，
     * 这里让快照与库里的终态对齐，刷新后回放不会再把这一轮画成运行中。
     *
     * <p>边界：只改非终态快照；已经是 SUCCESS / FAILED / CANCELLED 的卡片原样返回，
     * 不把真实完成或真实失败改写成中断。JSON 不可解析时也原样返回，展示层走兜底。
     *
     * @param json 落库 / 内存快照里的卡片 JSON
     * @return 改动后的 JSON；入参为空或不可解析时原样返回
     */
    public static String cancelled(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            JsonNode root = JSON.readTree(json);
            if (!(root instanceof ObjectNode body)) {
                return json;
            }
            String status = body.path("status").asText("").trim().toUpperCase();
            // 已经收口的卡片不再改写：成功、失败都是真实结果，不能因为停止请求晚到就改口
            if (isTerminal(status)) {
                return json;
            }
            body.put("status", "CANCELLED");
            /*
             * 已中断：不再有「当前节点」与「剩余」可言。
             * 只清节点级字段与等待时刻，整段上限 / 截止留着，回放仍能解释这段跑了多久。
             */
            if (body.get("limits") instanceof ObjectNode limits) {
                limits.remove("nodeCode");
                limits.remove("nodeName");
                limits.remove("nodeLimitMs");
                limits.remove("nodeDeadlineAt");
                limits.remove("waitingAt");
            }
            JsonNode nodes = body.get("nodes");
            if (nodes instanceof ArrayNode list) {
                for (JsonNode item : list) {
                    if (!(item instanceof ObjectNode node)) {
                        continue;
                    }
                    String nodeStatus = node.path("status").asText("").trim().toUpperCase();
                    if ("RUNNING".equals(nodeStatus) || "WAITING_HUMAN".equals(nodeStatus)
                            || "WAITING_REPLY".equals(nodeStatus)) {
                        // 与 WorkflowNodeRunRepository.cancelOpen 同一口径：在途 / 等待中的节点落成 CANCELLED
                        node.put("status", "CANCELLED");
                        node.put("note", "已中断");
                    } else if ("PENDING".equals(nodeStatus)) {
                        // 还没轮到的节点与库里一样落成 SKIPPED，避免刷新后仍显示「等待前置节点」
                        node.put("status", "SKIPPED");
                        node.put("note", "已跳过");
                    }
                }
            }
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            // 展示用快照：解析失败时宁可不改，也不把异常抛进停止链路
            return json;
        }
    }

    /**
     * 卡片 JSON 里的状态码，大写；解析失败返回空串。
     *
     * <p>用于停止补落库时判断「库里这张卡是不是已经收口」，避免拿更早的直播快照
     * 覆盖库里已经完成 / 已取消的终态卡片。
     *
     * @param json 卡片 JSON
     * @return 大写状态码；空 / 不可解析时返回空串
     */
    public static String statusOf(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            JsonNode root = JSON.readTree(json);
            if (!(root instanceof ObjectNode body)) {
                return "";
            }
            return body.path("status").asText("").trim().toUpperCase();
        } catch (JsonProcessingException ex) {
            return "";
        }
    }

    /**
     * 卡片标识：编码优先、名字兜底；同一轮里同名同编码视为同一张卡。
     *
     * @param json 卡片 JSON
     * @return 标识；空 / 不可解析时返回空串
     */
    public static String cardKey(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            JsonNode root = JSON.readTree(json);
            if (!(root instanceof ObjectNode body)) {
                return "";
            }
            String code = body.path("code").asText("").trim();
            return code.isEmpty() ? body.path("name").asText("").trim() : code;
        } catch (JsonProcessingException ex) {
            return "";
        }
    }

    /**
     * 是否终态。与 {@link #statusText(String)} 的分支保持一致。
     *
     * @param status 状态码；可空，大小写不敏感
     * @return true 表示 SUCCESS / FAILED / CANCELLED
     */
    public static boolean terminal(String status) {
        return isTerminal(status == null ? "" : status.trim().toUpperCase());
    }

    /**
     * 是否终态。与 {@link #statusText(String)} 的分支保持一致。
     *
     * @param status 大写状态码；可空
     * @return true 表示 SUCCESS / FAILED / CANCELLED
     */
    private static boolean isTerminal(String status) {
        return "SUCCESS".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    /**
     * 事件摘要：折叠 / 列表里的一句话，不进正文。
     *
     * @param view 执行卡片视图；可空
     * @return 形如「工作流「制度要点与报告」已完成」；视图为空时为空串
     */
    public static String summary(WorkflowRunView view) {
        if (view == null) {
            return "";
        }
        String name = view.name().isEmpty() ? view.code() : view.name();
        String label = name.isEmpty() ? "工作流" : "工作流「" + name + "」";
        return label + statusText(view.status());
    }

    /**
     * 运行状态 → 中文短语（卡片状态标签与事件摘要共用一份口径）。
     *
     * @param status 大写状态码；可空
     * @return 中文短语；未知状态按「运行中」处理，不伪造已完成
     */
    public static String statusText(String status) {
        String code = status == null ? "" : status.trim().toUpperCase();
        return switch (code) {
            case "SUCCESS" -> "已完成";
            case "FAILED" -> "运行失败";
            case "CANCELLED" -> "已取消";
            case "PAUSED_HUMAN" -> "等待人工处理";
            case "WAITING_REPLY" -> "等待你的回复";
            case "CREATED" -> "准备运行";
            default -> "运行中";
        };
    }
}
