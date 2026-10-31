package com.agenthub.tool.domain.repository;

import com.agenthub.tool.domain.model.ToolSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 工具源仓储。所属域：tool。实现不得把 Entity 交回应用层。
 */
public interface ToolSourceRepository {

    /**
     * @return 未删除的工具源，新的在前
     */
    List<ToolSource> listAll();

    /**
     * @param type 源类型，BUILTIN 或 MCP
     * @return 未删除源的 id
     */
    List<Long> listIdsByType(String type);

    /**
     * @param id 工具源 id
     * @return 未删除的源
     */
    Optional<ToolSource> findById(Long id);

    /**
     * 名称是否已被未删除源占用（精确匹配）。
     *
     * @param name      名称
     * @param excludeId 更新时排除自身；新建传 null
     * @return 已存在时为 true
     */
    boolean nameExists(String name, Long excludeId);

    /**
     * @param source     新源，含密文和掩码
     * @param operatorId 创建人
     * @return 新主键
     */
    Long insert(ToolSource source, Long operatorId);

    /**
     * 改名称、地址、传输、启停和备注。不碰鉴权头密文。
     *
     * @param source     已存在的源
     * @param operatorId 更新人
     */
    void updateProfile(ToolSource source, Long operatorId);

    /**
     * 只替换鉴权头密文和掩码。
     *
     * @param id         工具源 id
     * @param cipher     新密文
     * @param mask       新掩码
     * @param operatorId 更新人
     */
    void updateAuth(Long id, String cipher, String mask, Long operatorId);

    /**
     * 只替换密文与掩码，不改 updated_by。给主密钥轮换后的启动期迁移任务使用。
     *
     * @param id     工具源 id
     * @param cipher 新密文
     * @param mask   新掩码
     */
    void rewriteCipher(Long id, String cipher, String mask);

    /**
     * 记下一次同步。成功和失败都写，摘要不得含密钥。
     *
     * @param id         工具源 id
     * @param syncedTime   同步时间
     * @param ok         1 成功，0 失败
     * @param message    已脱敏摘要，最长 255
     * @param operatorId 操作人
     */
    void recordSync(Long id, LocalDateTime syncedTime, int ok, String message, Long operatorId);

    /**
     * @param id         工具源 id
     * @param operatorId 操作人
     */
    void logicalDelete(Long id, Long operatorId);
}
