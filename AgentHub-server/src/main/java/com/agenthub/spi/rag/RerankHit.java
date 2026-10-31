package com.agenthub.spi.rag;

/**
 * 单条重排命中。所属域：spi。
 * index 对应入参 documents 下标；score 为厂商相关性分（越高越相关）。
 *
 * @param index 原文档下标
 * @param score 相关性分数
 */
public record RerankHit(int index, double score) {
}
