package com.agenthub.rag.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 单条切片编辑请求。所属域：rag。
 * <p>
 * 只允许改正文与章节路径：序号、父块、偏移、向量代次由服务端维护，
 * 避免单条编辑把整篇切片的结构关系改乱。
 */
@Data
public class RagChunkUpdateReq {

    /** 新正文；空白不允许保存 */
    @NotBlank(message = "切片正文不能为空")
    private String content;

    /** 章节路径；不传表示沿用原值，传空串表示清空 */
    private String sectionPath;
}
