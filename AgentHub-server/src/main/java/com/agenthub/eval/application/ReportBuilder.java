package com.agenthub.eval.application;

import com.agenthub.eval.domain.model.EvalResult;
import com.agenthub.eval.domain.model.EvalRun;
import com.agenthub.eval.domain.model.EvalVerdict;
import com.agenthub.eval.domain.model.OpenJudgeStatus;
import com.agenthub.eval.domain.service.EvalRunConfig;
import com.agenthub.eval.infra.EvalProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 评测报告构建器。所属域：eval 的 application。
 * <p>run 终态后汇总 gate / 规则失败 / OpenJudge 均分（仅 OK）/ latency min-avg-max，
 * 写入 {@code report_json}；可选写 workspace Markdown 并回填 {@code report_path}。
 * <p>文案区分「规则失败」与「Judge 不可用/ERROR」。OpenJudge 分数<strong>不</strong>改 gate。
 * 写文件失败只打日志，尽量仍落 DB JSON，不把已收束 run 打成基础设施 FAILED。
 */
@Slf4j
@Component
public class ReportBuilder {

    private final ObjectMapper json;
    private final EvalProperties properties;

    /**
     * @param json       JSON
     * @param properties 全局报告缺省
     */
    public ReportBuilder(ObjectMapper json, EvalProperties properties) {
        this.json = json;
        this.properties = properties;
    }

    /**
     * 构建并回写 run 上的 report_json / report_path（不落库；调用方 updateState）。
     *
     * @param run     已终态的跑批
     * @param results 全部结果行
     * @return 同一 run 实例
     */
    public EvalRun buildAndAttach(EvalRun run, List<EvalResult> results) {
        if (run == null) {
            return null;
        }
        List<EvalResult> rows = results == null ? List.of() : results;
        JsonNode config = readConfig(run.getConfigJson());
        ObjectNode report = buildReportJson(run, rows, config);
        try {
            run.setReportJson(json.writeValueAsString(report));
        } catch (Exception ex) {
            log.warn("评测报告 JSON 序列化失败 run={}：{}", run.getId(), ex.toString());
            run.setReportJson(null);
        }

        boolean writeWorkspace = EvalRunConfig.reportWriteWorkspace(config,
                properties.getReport().isWriteWorkspace());
        boolean markdown = EvalRunConfig.reportMarkdown(config, properties.getReport().isMarkdown());
        if (!writeWorkspace || !markdown) {
            // 只写 DB：清掉旧 path，避免指向过期文件
            if (!writeWorkspace) {
                run.setReportPath(null);
            }
            return run;
        }
        try {
            Path dir = resolveWorkspaceDir(run.getId());
            Files.createDirectories(dir);
            Path md = dir.resolve("report.md");
            Files.writeString(md, renderMarkdown(report), StandardCharsets.UTF_8);
            // 同步落一份机读 JSON，便于人工对照
            Path jsonPath = dir.resolve("report.json");
            Files.writeString(jsonPath, run.getReportJson() == null ? "{}" : run.getReportJson(),
                    StandardCharsets.UTF_8);
            run.setReportPath(md.toAbsolutePath().normalize().toString());
        } catch (Exception ex) {
            log.warn("评测报告写 workspace 失败 run={}：{}（report_json 仍尽量保留）",
                    run.getId(), ex.toString());
        }
        return run;
    }

    /**
     * 机读报告结构。
     */
    ObjectNode buildReportJson(EvalRun run, List<EvalResult> rows, JsonNode config) {
        ObjectNode root = json.createObjectNode();
        ObjectNode meta = root.putObject("run");
        meta.put("id", run.getId() == null ? 0L : run.getId());
        meta.put("suite_id", run.getSuiteId() == null ? 0L : run.getSuiteId());
        meta.put("suite_version", run.getSuiteVersion() == null ? 0 : run.getSuiteVersion());
        meta.put("agent_id", run.getAgentId() == null ? 0L : run.getAgentId());
        meta.put("status", run.getStatus() == null ? "" : run.getStatus().name());
        if (run.getGatePassed() == null) {
            meta.putNull("gate_passed");
        } else {
            meta.put("gate_passed", run.getGatePassed() == 1);
        }
        meta.put("total_cases", nz(run.getTotalCases()));
        meta.put("passed", nz(run.getPassed()));
        meta.put("failed", nz(run.getFailed()));
        meta.put("errored", nz(run.getErrored()));
        meta.put("skipped", nz(run.getSkipped()));

        int judged = nz(run.getPassed()) + nz(run.getFailed());
        double passRate = judged <= 0 ? 0.0 : (double) nz(run.getPassed()) / (double) judged;
        root.put("pass_rate", passRate);
        root.put("incomplete", nz(run.getSkipped()) > 0 || nz(run.getErrored()) > 0);

        ArrayNode failedCases = root.putArray("failed_cases");
        ArrayNode erroredCases = root.putArray("errored_cases");
        ArrayNode skippedCases = root.putArray("skipped_cases");
        for (EvalResult one : rows) {
            if (one == null || one.getVerdict() == null) {
                continue;
            }
            if (one.getVerdict() == EvalVerdict.FAIL) {
                failedCases.add(caseBrief(one, true));
            } else if (one.getVerdict() == EvalVerdict.ERROR) {
                erroredCases.add(caseBrief(one, false));
            } else if (one.getVerdict() == EvalVerdict.SKIPPED) {
                skippedCases.add(caseBrief(one, false));
            }
        }

        root.set("openjudge", buildOpenjudgeBlock(rows, config));
        root.set("latency_ms", buildLatencyBlock(rows));
        root.set("usage", buildUsageBlock(rows));
        root.put("note", "gate_passed 仅由规则门禁决定；OpenJudge 为软评分，失败不翻转门禁。");
        return root;
    }

    private ObjectNode buildOpenjudgeBlock(List<EvalResult> rows, JsonNode config) {
        ObjectNode block = json.createObjectNode();
        // 与 Runner 同口径：开关开 + 有 Judge 模型实例才算启用；只开开关不算
        boolean enabled = EvalRunConfig.judgeEnabled(config);
        Long judgeModelInstanceId = EvalRunConfig.judgeModelInstanceId(config);
        block.put("enabled", enabled);
        if (judgeModelInstanceId != null) {
            block.put("judge_model_instance_id", judgeModelInstanceId);
        } else {
            block.putNull("judge_model_instance_id");
        }
        if (!enabled) {
            block.put("note", "OpenJudge 未启用（未选择打分模型或开关关闭，DISABLED）");
        }
        int ok = 0;
        int skipped = 0;
        int error = 0;
        int disabled = 0;
        double scoreSum = 0.0;
        int scoreCount = 0;
        for (EvalResult one : rows) {
            String status = one.getOpenjudgeStatus();
            if (status == null || status.isBlank()) {
                continue;
            }
            String upper = status.trim().toUpperCase(Locale.ROOT);
            if (OpenJudgeStatus.OK.name().equals(upper)) {
                ok++;
                Double score = extractOpenjudgeScore(one.getScoresJson());
                if (score != null) {
                    scoreSum += score;
                    scoreCount++;
                }
            } else if (OpenJudgeStatus.SKIPPED.name().equals(upper)) {
                skipped++;
            } else if (OpenJudgeStatus.ERROR.name().equals(upper)) {
                error++;
            } else if (OpenJudgeStatus.DISABLED.name().equals(upper)) {
                disabled++;
            }
        }
        ObjectNode dist = block.putObject("status_counts");
        dist.put("OK", ok);
        dist.put("SKIPPED", skipped);
        dist.put("ERROR", error);
        dist.put("DISABLED", disabled);
        block.put("ok_count", ok);
        if (scoreCount > 0) {
            block.put("avg_score", scoreSum / scoreCount);
        } else {
            block.putNull("avg_score");
        }
        return block;
    }

    private ObjectNode buildLatencyBlock(List<EvalResult> rows) {
        List<Integer> values = new ArrayList<>();
        for (EvalResult one : rows) {
            if (one.getLatencyMs() != null && one.getLatencyMs() >= 0) {
                values.add(one.getLatencyMs());
            }
        }
        return minAvgMaxInt(values);
    }

    private ObjectNode buildUsageBlock(List<EvalResult> rows) {
        List<Integer> totals = new ArrayList<>();
        for (EvalResult one : rows) {
            Integer total = extractUsageTotal(one.getArtifactSummaryJson());
            if (total != null) {
                totals.add(total);
            }
        }
        ObjectNode block = minAvgMaxInt(totals);
        block.put("field", "usage.total");
        return block;
    }

    private ObjectNode minAvgMaxInt(List<Integer> values) {
        ObjectNode node = json.createObjectNode();
        if (values.isEmpty()) {
            node.putNull("min");
            node.putNull("avg");
            node.putNull("max");
            return node;
        }
        int min = values.get(0);
        int max = values.get(0);
        long sum = 0;
        for (int v : values) {
            min = Math.min(min, v);
            max = Math.max(max, v);
            sum += v;
        }
        node.put("min", min);
        node.put("avg", (double) sum / (double) values.size());
        node.put("max", max);
        return node;
    }

    private ObjectNode caseBrief(EvalResult one, boolean includeRuleSummary) {
        ObjectNode node = json.createObjectNode();
        node.put("case_key", one.getCaseKey());
        node.put("verdict", one.getVerdict().name());
        if (one.getErrorMessage() != null && !one.getErrorMessage().isBlank()) {
            node.put("error_message", one.getErrorMessage());
        }
        if (includeRuleSummary) {
            node.put("kind", "规则门禁失败");
            String summary = firstFailedAssertion(one.getAssertionDetailsJson());
            if (summary != null) {
                node.put("rule_failure", summary);
            }
        } else if (one.getVerdict() == EvalVerdict.ERROR) {
            node.put("kind", "基础设施/试跑错误");
        } else {
            node.put("kind", "未执行/跳过");
        }
        if (one.getOpenjudgeStatus() != null) {
            node.put("openjudge_status", one.getOpenjudgeStatus());
        }
        return node;
    }

    private String renderMarkdown(ObjectNode report) {
        StringBuilder sb = new StringBuilder();
        JsonNode meta = report.get("run");
        sb.append("# 评测报告\n\n");
        sb.append("- 跑批 ID：").append(text(meta, "id")).append('\n');
        sb.append("- 状态：").append(text(meta, "status")).append('\n');
        sb.append("- 规则门禁 gate_passed：").append(text(meta, "gate_passed")).append('\n');
        sb.append("- 通过/失败/错误/跳过：")
                .append(text(meta, "passed")).append('/')
                .append(text(meta, "failed")).append('/')
                .append(text(meta, "errored")).append('/')
                .append(text(meta, "skipped")).append('\n');
        sb.append("- 通过率（仅 PASS+FAIL）：").append(String.format(Locale.ROOT, "%.2f",
                report.path("pass_rate").asDouble(0))).append('\n');
        sb.append('\n');
        sb.append("## 规则失败（门禁）\n\n");
        ArrayNode failed = (ArrayNode) report.get("failed_cases");
        if (failed == null || failed.isEmpty()) {
            sb.append("（无）\n\n");
        } else {
            for (JsonNode one : failed) {
                sb.append("- `").append(text(one, "case_key")).append("`：")
                        .append(text(one, "rule_failure")).append('\n');
            }
            sb.append('\n');
        }
        sb.append("## OpenJudge（软评分，不影响 gate）\n\n");
        JsonNode oj = report.get("openjudge");
        if (oj != null && !oj.path("enabled").asBoolean(false)) {
            sb.append("未启用（DISABLED）。\n\n");
        } else {
            sb.append("- OK 数：").append(text(oj, "ok_count")).append('\n');
            sb.append("- 均分（仅 OK）：").append(text(oj, "avg_score")).append('\n');
            JsonNode dist = oj == null ? null : oj.get("status_counts");
            if (dist != null) {
                sb.append("- 状态分布：OK=").append(text(dist, "OK"))
                        .append(", SKIPPED=").append(text(dist, "SKIPPED"))
                        .append(", ERROR=").append(text(dist, "ERROR"))
                        .append(", DISABLED=").append(text(dist, "DISABLED")).append('\n');
            }
            sb.append("\n> Judge 不可用 / ERROR 表示评分通道问题，**不是** Agent 规则失败。\n\n");
        }
        sb.append("## 耗时 latency_ms（min/avg/max）\n\n");
        JsonNode lat = report.get("latency_ms");
        sb.append(text(lat, "min")).append(" / ").append(text(lat, "avg")).append(" / ")
                .append(text(lat, "max")).append("\n");
        return sb.toString();
    }

    private Path resolveWorkspaceDir(Long runId) {
        String root = properties.getReport().getWorkspaceDir();
        if (root == null || root.isBlank()) {
            root = "./workspace-test";
        }
        return Path.of(root).resolve("eval-" + (runId == null ? "0" : runId));
    }

    private JsonNode readConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return json.createObjectNode();
        }
        try {
            JsonNode node = json.readTree(raw);
            return node == null || node.isNull() ? json.createObjectNode() : node;
        } catch (Exception ex) {
            return json.createObjectNode();
        }
    }

    private Double extractOpenjudgeScore(String scoresJson) {
        if (scoresJson == null || scoresJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = json.readTree(scoresJson);
            JsonNode oj = root.get("openjudge");
            if (oj != null && oj.has("score") && oj.get("score").isNumber()) {
                return oj.get("score").asDouble();
            }
        } catch (Exception ignored) {
            // 报告汇总容错
        }
        return null;
    }

    private Integer extractUsageTotal(String artifactSummaryJson) {
        if (artifactSummaryJson == null || artifactSummaryJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = json.readTree(artifactSummaryJson);
            JsonNode usage = root.get("usage");
            if (usage != null && usage.has("total") && usage.get("total").isNumber()) {
                return usage.get("total").asInt();
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    private String firstFailedAssertion(String detailsJson) {
        if (detailsJson == null || detailsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode arr = json.readTree(detailsJson);
            if (!arr.isArray()) {
                return null;
            }
            for (JsonNode one : arr) {
                if (one != null && one.path("ok").asBoolean(true) == false) {
                    String type = one.path("type").asText("?");
                    String message = one.path("message").asText("");
                    return type + ": " + message;
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isNull() || !node.has(field) || node.get(field).isNull()) {
            return "-";
        }
        JsonNode v = node.get(field);
        if (v.isFloatingPointNumber()) {
            return String.format(Locale.ROOT, "%.2f", v.asDouble());
        }
        return v.asText();
    }
}
