package com.agenthub.eval.application;

import com.agenthub.eval.api.dto.EvalCaseResp;
import com.agenthub.eval.api.dto.EvalResultResp;
import com.agenthub.eval.api.dto.EvalRunResp;
import com.agenthub.eval.api.dto.EvalSuiteDetailResp;
import com.agenthub.eval.api.dto.EvalSuiteResp;
import com.agenthub.eval.domain.model.EvalCase;
import com.agenthub.eval.domain.model.EvalResult;
import com.agenthub.eval.domain.model.EvalRun;
import com.agenthub.eval.domain.model.EvalSuite;
import com.agenthub.eval.domain.service.EvalRunConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 领域模型 → API 出参的装配。所属域：eval。
 * <p>职责单一：把库内 JSON 文本解析成节点／数组，并把只读标记算好。
 * <p>降级：库里若是脏 JSON（历史事故／人工改库），解析失败只打日志并回 null，
 * **不**让列表接口整体 500 —— 一条坏数据不该把整个用例库读不出来。
 */
@Slf4j
@Component
public class EvalAssembler {

    private static final TypeReference<List<String>> TAGS_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper json;

    /**
     * @param json JSON 解析器（Spring 容器提供的全局实例）
     */
    public EvalAssembler(ObjectMapper json) {
        this.json = json;
    }

    /**
     * @param suite 套件
     * @return 列表行（不含用例）
     */
    public EvalSuiteResp toResp(EvalSuite suite) {
        EvalSuiteResp resp = new EvalSuiteResp();
        resp.setId(suite.getId());
        resp.setTenantId(suite.getTenantId());
        resp.setSuiteKey(suite.getSuiteKey());
        resp.setName(suite.getName());
        resp.setDescription(suite.getDescription());
        resp.setCurrentVersion(suite.getCurrentVersion());
        resp.setDefaultConfig(readNode(suite.getDefaultConfigJson()));
        resp.setEnabled(suite.getEnabled());
        resp.setReadOnly(suite.isPlatformOwned());
        resp.setCreateBy(suite.getCreatedBy());
        resp.setCreateTime(suite.getCreateTime());
        resp.setUpdateBy(suite.getUpdatedBy());
        resp.setUpdateTime(suite.getUpdateTime());
        return resp;
    }

    /**
     * @param suite 套件
     * @param cases 当前版本用例
     * @return 详情（元数据 + 用例）
     */
    public EvalSuiteDetailResp toDetail(EvalSuite suite, List<EvalCase> cases) {
        EvalSuiteDetailResp resp = new EvalSuiteDetailResp();
        resp.setId(suite.getId());
        resp.setTenantId(suite.getTenantId());
        resp.setSuiteKey(suite.getSuiteKey());
        resp.setName(suite.getName());
        resp.setDescription(suite.getDescription());
        resp.setCurrentVersion(suite.getCurrentVersion());
        resp.setDefaultConfig(readNode(suite.getDefaultConfigJson()));
        resp.setEnabled(suite.getEnabled());
        resp.setReadOnly(suite.isPlatformOwned());
        resp.setCases(cases.stream().map(this::toCaseResp).toList());
        resp.setCreateBy(suite.getCreatedBy());
        resp.setCreateTime(suite.getCreateTime());
        resp.setUpdateBy(suite.getUpdatedBy());
        resp.setUpdateTime(suite.getUpdateTime());
        return resp;
    }

    /**
     * @param one 用例
     * @return 用例出参
     */
    public EvalCaseResp toCaseResp(EvalCase one) {
        EvalCaseResp resp = new EvalCaseResp();
        resp.setId(one.getId());
        resp.setSuiteVersion(one.getSuiteVersion());
        resp.setCaseKey(one.getCaseKey());
        resp.setTitle(one.getTitle());
        resp.setTags(readTags(one.getTagsJson()));
        resp.setInput(readNode(one.getInputJson()));
        resp.setExpect(readNode(one.getExpectJson()));
        resp.setAssertions(readNode(one.getAssertionsJson()));
        resp.setScoring(readNode(one.getScoringJson()));
        resp.setEnabled(one.getEnabled());
        resp.setSortOrder(one.getSortOrder());
        return resp;
    }

    /**
     * JSON 文本 → 节点；空串／null 一律回 null。
     *
     * @param raw 库内文本
     * @return 节点或 null
     */
    public JsonNode readNode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readTree(raw);
        } catch (Exception ex) {
            log.warn("评测 JSON 列解析失败，按 null 返回（请检查库内数据）");
            return null;
        }
    }

    /**
     * 标签数组解析；脏数据回空数组，前端不必判 null。
     *
     * @param raw 库内文本
     * @return 标签列表
     */
    public List<String> readTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<String> tags = json.readValue(raw, TAGS_TYPE);
            return tags == null ? List.of() : tags;
        } catch (Exception ex) {
            log.warn("评测标签 JSON 解析失败，按空数组返回");
            return List.of();
        }
    }

    /**
     * 跑批 → 出参。状态以库内字符串原样透出（前端不做状态推演）。
     *
     * @param run 运行行
     * @return 出参
     */
    public EvalRunResp toRunResp(EvalRun run) {
        EvalRunResp resp = new EvalRunResp();
        resp.setId(run.getId());
        resp.setTenantId(run.getTenantId());
        resp.setSuiteId(run.getSuiteId());
        resp.setSuiteVersion(run.getSuiteVersion());
        resp.setAgentId(run.getAgentId());
        resp.setAgentSnapshotHash(run.getAgentSnapshotHash());
        resp.setStatus(run.getStatus() == null ? null : run.getStatus().name());
        resp.setGatePassed(run.getGatePassed());
        resp.setTotalCases(run.getTotalCases());
        resp.setPassed(run.getPassed());
        resp.setFailed(run.getFailed());
        resp.setErrored(run.getErrored());
        resp.setSkipped(run.getSkipped());
        resp.setOpenjudgeEnabled(run.getOpenjudgeEnabled());
        JsonNode configNode = readNode(run.getConfigJson());
        resp.setConfig(configNode);
        // 打分模型 id 从快照回显；历史行没有该字段时为 null（= 不打分）
        resp.setJudgeModelInstanceId(EvalRunConfig.judgeModelInstanceId(configNode));
        resp.setReportPath(run.getReportPath());
        resp.setReport(readNode(run.getReportJson()));
        resp.setTriggerUserId(run.getTriggerUserId());
        resp.setErrorMessage(run.getErrorMessage());
        resp.setStartedTime(run.getStartedTime());
        resp.setFinishedTime(run.getFinishedTime());
        resp.setCreateBy(run.getCreatedBy());
        resp.setCreateTime(run.getCreateTime());
        resp.setUpdateBy(run.getUpdatedBy());
        resp.setUpdateTime(run.getUpdateTime());
        return resp;
    }

    /**
     * 单 case 结果 → 出参。verdict 为空即「尚未执行」，不擅自补成 FAIL。
     *
     * @param one 结果行
     * @return 出参
     */
    public EvalResultResp toResultResp(EvalResult one) {
        EvalResultResp resp = new EvalResultResp();
        resp.setId(one.getId());
        resp.setRunId(one.getRunId());
        resp.setCaseKey(one.getCaseKey());
        resp.setCaseId(one.getCaseId());
        resp.setSortOrder(one.getSortOrder());
        resp.setVerdict(one.getVerdict() == null ? null : one.getVerdict().name());
        resp.setRulePassed(one.getRulePassed());
        resp.setOpenjudgeStatus(one.getOpenjudgeStatus());
        resp.setScores(readNode(one.getScoresJson()));
        resp.setArtifactSummary(readNode(one.getArtifactSummaryJson()));
        resp.setSessionId(one.getSessionId());
        resp.setTraceId(one.getTraceId());
        resp.setCacheHit(one.getCacheHit());
        resp.setPromptBreakdown(readNode(one.getPromptBreakdownJson()));
        resp.setLatencyMs(one.getLatencyMs());
        resp.setAssertionDetails(readNode(one.getAssertionDetailsJson()));
        resp.setErrorMessage(one.getErrorMessage());
        resp.setStartedTime(one.getStartedTime());
        resp.setFinishedTime(one.getFinishedTime());
        return resp;
    }
}
