package com.agenthub.rag.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增 Agent 知识库绑定。所属域：rag。
 * overridesJson 为空表示全用 KB 的 retrieval_config。
 * kbId 是 Long，只能用 {@link NotNull}；误用 {@code @NotBlank} 会在校验阶段抛
 * UnexpectedTypeException，被全局处理成「系统繁忙」。
 */
@Data
public class KbBindCreateReq {

    /** 知识库 id，必须对当前用户可见且可用 */
    @NotNull(message = "不能为空")
    private Long kbId;

    /** 1 启用 0 停用，空按启用 */
    private Integer status;

    /** 检索覆盖项 JSON（topK / rrfK / rrfEnabled / rerankEnabled 等），可空 */
    @Size(max = 1000, message = "最长 1000")
    private String overridesJson;
}
