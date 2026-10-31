package com.agenthub.model.domain.repository;

import com.agenthub.model.domain.model.ModelCredential;

import java.util.List;
import java.util.Optional;

/**
 * 凭证仓储。所属域：model。查询可以带回密文给应用服务解密，响应组装时必须丢掉密文。
 */
public interface ModelCredentialRepository {

    /**
     * @return 全部未删除凭证
     */
    List<ModelCredential> listAll();

    /**
     * @param providerId 供应商 id
     * @return 该供应商下未删除的凭证
     */
    List<ModelCredential> listByProviderId(Long providerId);

    /**
     * @param id 凭证 id
     * @return 未删除的凭证，含密文
     */
    Optional<ModelCredential> findById(Long id);

    /**
     * 未删除范围内，同一供应商的凭证名是否已被占用。
     *
     * @param providerId 供应商 id
     * @param name       凭证名
     * @param excludeId  更新时排除自己；新建传 null
     * @return 已存在时为 true
     */
    boolean nameExists(Long providerId, String name, Long excludeId);

    /**
     * @param credential 明文已经换成密文和掩码
     * @param operatorId 创建人
     * @return 新主键
     */
    Long insert(ModelCredential credential, Long operatorId);

    /**
     * @param credential 待更新的名称、状态或新密文
     * @param operatorId 更新人
     */
    void update(ModelCredential credential, Long operatorId);

    /**
     * 只替换密文与掩码，不改名称、状态与 updated_by。给主密钥轮换后的启动期迁移任务使用。
     *
     * @param id     凭证 id
     * @param cipher 新密文
     * @param mask   新掩码
     */
    void rewriteCipher(Long id, String cipher, String mask);

    /**
     * @param id         凭证 id
     * @param operatorId 操作人
     */
    void logicalDelete(Long id, Long operatorId);
}
