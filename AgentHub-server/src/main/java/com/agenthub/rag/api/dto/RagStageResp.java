package com.agenthub.rag.api.dto;

import lombok.Data;

/**
 * 入库阶段摘要响应。所属域：rag。
 * <p>
 * 对外固定五步：解析 / 结构识别 / 切片 / 向量化 / 写入。
 * 成功与失败都保留；前端阶段条点击某一步时展示耗时、简要产物或失败原因。
 */
@Data
public class RagStageResp {

    /** 阶段名：解析 / 结构识别 / 切片 / 向量化 / 写入 */
    private String stage;
    /** RUNNING / SUCCESS / FAILED / SKIPPED */
    private String status;
    /** 耗时（毫秒）；运行中为空 */
    private Long elapsedMs;
    /** 简要产物或失败原因 */
    private String summary;
}
