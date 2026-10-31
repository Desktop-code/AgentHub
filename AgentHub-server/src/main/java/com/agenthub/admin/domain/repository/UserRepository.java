package com.agenthub.admin.domain.repository;

import com.agenthub.admin.domain.model.DataScopeSpec;
import com.agenthub.admin.domain.model.User;
import com.agenthub.admin.domain.model.UserQuery;
import com.agenthub.common.api.PageResult;

import java.util.Collection;
import java.util.Optional;

/**
 * 平台用户仓储。所属域：admin。实现不得把 Entity 交回应用层。
 */
public interface UserRepository {

    /**
     * 按登录名查找，不含已逻辑删除的行。密码哈希仍在领域对象上，供登录比对。
     *
     * @param username 登录名
     * @return 用户；不存在时为空
     */
    Optional<User> findByUsername(String username);

    /**
     * @param id 用户 id
     * @return 未删除用户
     */
    Optional<User> findById(Long id);

    /**
     * 含已删除行。唯一键不带 deleted，软删后同名仍算占用。
     *
     * @param username 登录名
     * @return 已占用时为 true
     */
    boolean usernameTaken(String username);

    /**
     * @param query 分页和筛选
     * @param scope 数据范围，不可为 null
     * @return 带角色 id 和名称的一页用户
     */
    PageResult<User> page(UserQuery query, DataScopeSpec scope);

    /**
     * @param user 已填好哈希和资料的新用户
     * @return 新主键
     */
    Long insert(User user);

    /**
     * 只改资料，不改密码和登录名。
     *
     * @param user 含 id 的资料
     */
    void updateProfile(User user);

    /**
     * @param id           用户 id
     * @param passwordHash BCrypt 哈希
     * @param operatorId   操作者，可空
     */
    void updatePassword(Long id, String passwordHash, Long operatorId);

    /**
     * 登录成功后回写最近登录时间和 IP。
     *
     * @param id 用户 id
     * @param ip 客户端 IP
     */
    void updateLoginAudit(Long id, String ip);

    /**
     * @param id         用户 id
     * @param operatorId 操作者
     */
    void logicDelete(Long id, Long operatorId);

    /**
     * 物理替换 sys_user_role。传入空集合表示清空角色。
     *
     * @param userId  用户 id
     * @param roleIds 新的角色 id
     */
    void replaceRoles(Long userId, Collection<Long> roleIds);

    /**
     * 用户当前是否有效，且拥有启用中的 admin 角色。
     *
     * @param userId 用户 id
     * @return 是最后一个管理员候选时为 true
     */
    boolean activeAdmin(Long userId);

    /**
     * @param userId 被排除的用户
     * @return 其他仍启用、且拥有启用 admin 角色的人数
     */
    long countOtherActiveAdmins(Long userId);
}
