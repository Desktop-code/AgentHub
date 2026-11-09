package com.agenthub.admin.application;

import com.agenthub.admin.api.dto.UserCreateReq;
import com.agenthub.admin.api.dto.UserPageReq;
import com.agenthub.admin.api.dto.UserResp;
import com.agenthub.admin.api.dto.UserRoleReq;
import com.agenthub.admin.api.dto.UserUpdateReq;
import com.agenthub.admin.domain.enums.PermissionCodes;
import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.admin.domain.model.Dept;
import com.agenthub.admin.domain.model.Role;
import com.agenthub.admin.domain.model.User;
import com.agenthub.admin.domain.model.UserQuery;
import com.agenthub.admin.domain.repository.DeptRepository;
import com.agenthub.admin.domain.repository.RoleRepository;
import com.agenthub.admin.domain.repository.UserRepository;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.config.AgentHubProperties;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.security.PasswordHasher;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.spi.cache.AclCacheInvalidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 用户用例。所属域：admin。
 * 约束：不能删/停用自己，不能动掉最后一个 admin 角色用户；接口不接收也不返回密码。
 */
@Service
public class UserAppService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final DeptRepository depts;
    private final ScopeQueryService scopes;
    private final PasswordHasher passwordHasher;
    private final AgentHubProperties properties;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;
    private final AclCacheInvalidator aclCache;

    /**
     * @param users           用户仓储
     * @param roles           校验角色是否存在
     * @param depts           校验部门
     * @param scopes          数据范围
     * @param passwordHasher  初始密码哈希
     * @param properties      重置口令配置
     * @param operatorContext 当前操作者
     * @param operLogWriter   操作日志
     * @param aclCache        访问快照失效（提交后按用户精确删）
     */
    public UserAppService(UserRepository users, RoleRepository roles, DeptRepository depts, ScopeQueryService scopes,
                          PasswordHasher passwordHasher, AgentHubProperties properties, OperatorContext operatorContext,
                          OperLogWriter operLogWriter, AclCacheInvalidator aclCache) {
        this.users = users;
        this.roles = roles;
        this.depts = depts;
        this.scopes = scopes;
        this.passwordHasher = passwordHasher;
        this.properties = properties;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
        this.aclCache = aclCache;
    }

    /**
     * @param req 分页和筛选
     * @return 当前数据范围内的用户
     */
    public PageResult<UserResp> page(UserPageReq req) {
        UserQuery query = new UserQuery();
        query.setCurrent(req.getCurrent());
        query.setSize(req.getSize());
        query.setUsername(LikeTexts.contains(req.getUsername()));
        query.setStatus(req.getStatus());
        query.setDeptId(req.getDeptId());
        PageResult<User> page = users.page(query, scopes.current());
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords().stream().map(this::toResp).toList());
    }

    /**
     * @param id 用户 id
     * @return 用户资料
     * @throws BizException 不可见或不存在
     */
    public UserResp get(Long id) {
        User user = users.findById(id).orElse(null);
        scopes.assertUserVisible(user, scopes.current());
        return toResp(user);
    }

    /**
     * 新建用户。is_admin 固定为否，避免通过接口复制超管。
     *
     * @param req 资料和角色
     * @return 创建结果，不含明文密码
     * @throws BizException 登录名占用、部门或角色不存在
     */
    @Transactional
    public UserResp create(UserCreateReq req) {
        if (users.usernameTaken(req.getUsername())) {
            throw new BizException(ErrorCode.DUPLICATE, "登录名已存在（含已删除账号）");
        }
        assertDept(req.getDeptId());
        assertRoles(req.getRoleIds());
        User user = new User();
        user.setUsername(req.getUsername().trim());
        user.setNickname(req.getNickname());
        user.setDeptId(req.getDeptId());
        user.setEmail(req.getEmail());
        user.setPhone(req.getPhone());
        user.setStatus(normalizeStatus(req.getStatus()));
        user.setPlatformAdmin(false);
        user.setPasswordHash(passwordHasher.hash(initialPassword()));
        Long id = users.insert(user);
        users.replaceRoles(id, req.getRoleIds());
        operLogWriter.write("user", "create", "user", String.valueOf(id), "创建用户 " + user.getUsername());
        return get(id);
    }

    /**
     * @param id  用户 id
     * @param req 可改资料
     * @return 更新后的用户
     * @throws BizException 停用自己或最后一个管理员
     */
    @Transactional
    public UserResp update(Long id, UserUpdateReq req) {
        User existing = users.findById(id).orElse(null);
        scopes.assertUserVisible(existing, scopes.current());
        int status = normalizeStatus(req.getStatus());
        if (!RecordStatus.isEnabled(status) && id.equals(operatorContext.requireUserId())) {
            throw new BizException(ErrorCode.CANNOT_CHANGE_SELF, "不能停用当前登录用户");
        }
        if (!RecordStatus.isEnabled(status)) {
            assertKeepsAdmin(id, false);
        }
        assertDept(req.getDeptId());
        existing.setDeptId(req.getDeptId());
        existing.setNickname(req.getNickname());
        existing.setEmail(req.getEmail());
        existing.setPhone(req.getPhone());
        existing.setStatus(status);
        users.updateProfile(existing);
        // 启停/部门变化会影响该用户的权限与 /me 展示：提交后按该用户精确失效
        aclCache.evictUser(id);
        operLogWriter.write("user", "update", "user", String.valueOf(id), "更新用户 " + existing.getUsername());
        return get(id);
    }

    /**
     * @param id 用户 id
     * @throws BizException 删除自己或最后一个管理员
     */
    @Transactional
    public void delete(Long id) {
        User existing = users.findById(id).orElse(null);
        scopes.assertUserVisible(existing, scopes.current());
        if (id.equals(operatorContext.requireUserId())) {
            throw new BizException(ErrorCode.CANNOT_CHANGE_SELF, "不能删除当前登录用户");
        }
        assertKeepsAdmin(id, false);
        users.logicDelete(id, operatorContext.requireUserId());
        // 删除用户后其快照不应再被命中：提交后精确失效
        aclCache.evictUser(id);
        operLogWriter.write("user", "delete", "user", String.valueOf(id), "删除用户 " + existing.getUsername());
    }

    /**
     * 重置为配置中的初始密码。响应和日志都不带明文。
     *
     * @param id 用户 id
     */
    @Transactional
    public void resetPassword(Long id) {
        User existing = users.findById(id).orElse(null);
        scopes.assertUserVisible(existing, scopes.current());
        users.updatePassword(id, passwordHasher.hash(initialPassword()), operatorContext.requireUserId());
        operLogWriter.write("user", "resetPwd", "user", String.valueOf(id), "重置密码");
    }

    /**
     * @param id  用户 id
     * @param req 新的角色集合
     * @throws BizException 角色不存在，或会拿走最后一个管理员
     */
    @Transactional
    public void assignRoles(Long id, UserRoleReq req) {
        User existing = users.findById(id).orElse(null);
        scopes.assertUserVisible(existing, scopes.current());
        assertRoles(req.getRoleIds());
        Role admin = roles.findByCode(PermissionCodes.ADMIN_ROLE).orElse(null);
        boolean keepAdmin = admin != null && req.getRoleIds().contains(admin.getId());
        boolean remain = RecordStatus.isEnabled(existing.getStatus()) && keepAdmin;
        assertKeepsAdmin(id, remain);
        users.replaceRoles(id, req.getRoleIds());
        // 只影响这一个用户的角色集合：精确删该用户快照，不误伤其它用户
        aclCache.evictUser(id);
        operLogWriter.write("user", "grant", "user", String.valueOf(id), "分配角色");
    }

    /**
     * 目标操作之后如果不再是有效管理员，则系统里必须还剩别人。
     */
    private void assertKeepsAdmin(Long userId, boolean remainActiveAdmin) {
        if (remainActiveAdmin) {
            return;
        }
        if (users.activeAdmin(userId) && users.countOtherActiveAdmins(userId) == 0) {
            throw new BizException(ErrorCode.LAST_ADMIN);
        }
    }

    private void assertDept(Long deptId) {
        if (deptId == null) {
            return;
        }
        Dept dept = depts.findById(deptId).orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "部门不存在"));
        if (!scopes.deptVisible(dept, scopes.current())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "无权使用该部门");
        }
    }

    private void assertRoles(List<Long> roleIds) {
        if (roleIds == null) {
            return;
        }
        for (Long roleId : roleIds) {
            if (roles.findById(roleId).isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "角色不存在");
            }
        }
    }

    private String initialPassword() {
        String raw = properties.getSecurity().getResetPassword();
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未配置初始密码");
        }
        return raw;
    }

    private int normalizeStatus(Integer status) {
        return RecordStatus.isEnabled(status) ? RecordStatus.ENABLED.getCode() : RecordStatus.DISABLED.getCode();
    }

    private UserResp toResp(User user) {
        UserResp resp = new UserResp();
        resp.setId(user.getId());
        resp.setUsername(user.getUsername());
        resp.setNickname(user.getNickname());
        resp.setDeptId(user.getDeptId());
        resp.setDeptName(user.getDeptName());
        resp.setEmail(user.getEmail());
        resp.setPhone(user.getPhone());
        resp.setStatus(user.getStatus());
        resp.setPlatformAdmin(user.isPlatformAdmin());
        resp.setLastLoginTime(user.getLastLoginTime());
        resp.setCreateTime(user.getCreateTime());
        resp.setRoleIds(user.getRoleIds());
        resp.setRoleNames(user.getRoleNames());
        return resp;
    }
}
