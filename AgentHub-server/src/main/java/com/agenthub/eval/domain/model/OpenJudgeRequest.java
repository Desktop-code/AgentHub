package com.agenthub.eval.domain.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenJudge 评分入参。所属域：eval 的 domain。
 * <p>由 Runner 从 EvalArtifact + case expect/scoring + 合并配置装配；Adapter 只读本对象。
 */
@Data
public class OpenJudgeRequest {

    /** 用例键（便于 sidecar 日志） */
    private String caseKey;
    /** 试跑产物 */
    private EvalArtifact artifact;
    /** expect.reference，可空 */
    private String reference;
    /** expect.expect_tools，可空 */
    private List<String> expectTools = new ArrayList<>();
    /** 已解析的 grader 名列表（非空） */
    private List<String> graders = new ArrayList<>();
    /** sidecar 基址，如 http://127.0.0.1:18765 */
    private String baseUrl;
    /** 单次调用超时（毫秒） */
    private long timeoutMs = 60_000L;
}
