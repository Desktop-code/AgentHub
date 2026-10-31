package com.agenthub.model.infra.adapter;

import com.agenthub.model.domain.model.ModelCapability;

/**
 * 一次探活的出站参数。所属域：model。apiKey 只留在适配器内存里，禁止写入日志。
 *
 * @param baseUrl    供应商地址
 * @param apiKey     已解密的厂商 Key
 * @param modelId    厂商模型 ID
 * @param capability 用途；经 ModelPurposeMatrix 决定 Chat / Embedding / Rerank / 未适配
 */
public record ProbeCommand(String baseUrl, String apiKey, String modelId, ModelCapability capability) {
}
