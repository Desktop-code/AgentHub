package com.agenthub.rag.domain.service;

/**
 * Collection 命名规则。所属域：rag。
 * <p>
 * 对外只说「知识库」，Collection 名只在 rag 域内部使用。命名必须满足 Milvus 要求
 * （字母或下划线开头，仅字母/数字/下划线，长度 ≤ 255），并且能从名字反推 kb_id，便于删库和排障。
 */
public final class RagCollectionName {

    /** 统一前缀，避免与同实例上其它业务集合撞名 */
    public static final String PREFIX = "agenthub_kb_";

    /** Milvus 集合名长度上限；再留余量给将来可能的分区后缀 */
    private static final int MAX_LENGTH = 128;

    private RagCollectionName() {
    }

    /**
     * 生成集合名：{@code agenthub_kb_{kbId}}。
     * <p>
     * 只用 kb_id 而不拼名称：名称可改、可含中文与空格，拼进集合名会带来转义和重命名问题。
     *
     * @param kbId 知识库主键，必须为正数
     * @return 集合名
     * @throws IllegalArgumentException kbId 非法
     */
    public static String of(Long kbId) {
        if (kbId == null || kbId <= 0) {
            throw new IllegalArgumentException("知识库 id 非法，无法生成 Collection 名");
        }
        String name = PREFIX + kbId;
        if (name.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Collection 名过长: " + name);
        }
        return name;
    }

    /**
     * 校验外部传入的集合名只可能由本规则产生，防止误删其它集合。
     *
     * @param collectionName 已落库的集合名
     * @return true 表示形如 agenthub_kb_{数字}
     */
    public static boolean ownedByPlatform(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) {
            return false;
        }
        return collectionName.matches("^" + PREFIX + "\\d+$");
    }
}
