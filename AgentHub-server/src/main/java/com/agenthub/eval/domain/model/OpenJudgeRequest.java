package com.agenthub.eval.domain.model;

import lombok.Data;
import lombok.ToString;

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
    /** 本 run 指定的 Judge 模型实例 id；非空才会发起评分（控制台跑批可选） */
    private Long judgeModelInstanceId;
    /**
     * 本 run 打分模型的出站凭据（model / base_url / api_key）。
     * <p>由 Java 从模型中心解密后经本机环回透传给 sidecar；<b>禁止</b>落库或打日志。
     * <p>为空时 sidecar 回落自身环境变量——仅用于「未指定实例」的历史/手工调用。
     */
    @ToString.Exclude
    private OpenJudgeJudge judge;
    /** sidecar 基址，如 http://127.0.0.1:18765 */
    private String baseUrl;
    /** 单次调用超时（毫秒） */
    private long timeoutMs = 60_000L;
}
