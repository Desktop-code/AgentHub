package com.agenthub.memory.domain.service;

import java.util.regex.Pattern;

/**
 * 记忆专用 Milvus collection 命名规则。所属域：memory。
 * <p>
 * 为什么要校验前缀：RAG 知识库集合叫 {@code agenthub_kb_*}，记忆与 KB 共用同一 Milvus 实例，
 * 一旦 YAML 填错就可能把长期记忆写进知识库集合（或反向删掉 KB 数据）。
 * 这里要求名字必须落在 {@code agenthub_memory_*} 命名空间内；不满足时上层只置 isReady=false 并打日志，
 * 不自动改写名字、不回落任何默认集合。
 */
public final class MemoryCollectionName {

    /** 记忆 collection 统一前缀；与 RAG 的 {@code agenthub_kb_} 天然隔离 */
    public static final String PREFIX = "agenthub_memory_";

    /** Milvus 集合名长度上限；留出后续分区后缀余量 */
    private static final int MAX_LENGTH = 128;

    /** Milvus 合法集合名：字母或下划线开头，仅字母/数字/下划线 */
    private static final Pattern MILVUS_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private MemoryCollectionName() {
    }

    /**
     * 校验 YAML 传入的集合名是否由记忆域管理。
     *
     * @param collectionName 配置的记忆 collection，可空
     * @return true 表示以 {@link #PREFIX} 开头且符合 Milvus 命名与长度要求
     */
    public static boolean isManaged(String collectionName) {
        if (collectionName == null) {
            return false;
        }
        String name = collectionName.trim();
        return name.length() <= MAX_LENGTH
                && name.startsWith(PREFIX)
                && MILVUS_NAME.matcher(name).matches();
    }
}
