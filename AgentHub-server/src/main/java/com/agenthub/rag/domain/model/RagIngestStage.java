package com.agenthub.rag.domain.model;

import lombok.Data;

/**
 * 一次入库中的单个阶段摘要。所属域：rag。
 * <p>
 * 与 {@code rag_ingest_task.stage_trace} 的 JSON 元素一一对应：
 * 对外固定五步「解析 / 结构识别 / 切片 / 向量化 / 写入」，成功与失败都保留，
 * 供文档页阶段条展示耗时、简要产物与失败原因。
 */
@Data
public class RagIngestStage {

    /** 阶段名：解析 / 结构识别 / 切片 / 向量化 / 写入 */
    private String stage;
    /** RUNNING / SUCCESS / FAILED / SKIPPED */
    private String status;
    /** 该阶段耗时（毫秒）；运行中为 null */
    private Long elapsedMs;
    /** 简要产物或失败原因，单个阶段不超过 200 字 */
    private String summary;
}
