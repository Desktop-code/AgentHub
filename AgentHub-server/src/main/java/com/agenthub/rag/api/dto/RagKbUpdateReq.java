package com.agenthub.rag.api.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 编辑知识库请求。所属域：rag。
 * 一期只允许改资料与检索参数：Embedding 与维度建库即锁定，改名/换模型必须新建库。
 */
@Data
public class RagKbUpdateReq {

    /** 名称，未删除范围唯一 */
    @Size(max = 100, message = "最长 100")
    private String name;

    /** 描述 */
    @Size(max = 512, message = "最长 512")
    private String description;

    /** PUBLIC / PRIVATE；RESTRICTED 一期拒绝 */
    @Size(max = 20, message = "最长 20")
    private String visibility;

    /** 可选 Rerank 模型实例 id；传 0 表示清空 */
    private Long rerankModelId;

    /** 检索参数 JSON */
    @Size(max = 4000, message = "最长 4000")
    private String retrievalConfig;

    /** 入库参数 JSON */
    @Size(max = 4000, message = "最长 4000")
    private String ingestConfig;
}
