package com.agenthub.admin.application;

import com.agenthub.admin.domain.enums.DataScope;
import com.agenthub.admin.domain.model.App;
import com.agenthub.admin.domain.model.DataScopeSpec;
import com.agenthub.admin.domain.model.Dept;
import com.agenthub.admin.domain.model.Role;
import com.agenthub.admin.domain.model.RoleGrant;
import com.agenthub.admin.domain.model.User;
import com.agenthub.admin.domain.repository.DeptRepository;
import com.agenthub.admin.domain.repository.RoleRepository;
import com.agenthub.admin.domain.repository.UserRepository;
import com.agenthub.admin.domain.service.DataScopeResolver;
import com.agenthub.admin.domain.service.DeptPath;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 读取当前登录人的数据范围，并判断单条记录是否可见。所属域：admin。
 * 不可见时返回 404，避免用 403 告诉对方这条记录存在。
 */
@Service
public class ScopeQueryService {

    private final OperatorContext operatorContext;
    private final UserRepository users;
    private final RoleRepository roles;
    private final DeptRepository depts;
    private final DataScopeResolver resolver;

    /**
     * @param operatorContext 当前登录人
     * @param users           用户资料
     * @param roles           角色与自定义部门
     * @param depts           部门 path
     * @param resolver        多角色并集
     */
    public ScopeQueryService(OperatorContext operatorContext, UserRepository users, RoleRepository roles,
                             DeptRepository depts, DataScopeResolver resolver) {
        this.operatorContext = operatorContext;
        this.users = users;
        this.roles = roles;
        this.depts = depts;
        this.resolver = resolver;
    }

    /**
     * @return 当前请求的数据范围
     * @throws BizException 未登录或用户已被删
     */
    public DataScopeSpec current() {
        long userId = operatorContext.requireUserId();
        User user = users.findById(userId).orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED));
        String path = null;
        if (user.getDeptId() != null) {
            path = depts.findById(user.getDeptId()).map(Dept::getPath).orElse(null);
        }
        List<RoleGrant> grants = new ArrayList<>();
        for (Role role : roles.findByUserId(userId)) {
            List<Long> custom = role.getDataScope() == DataScope.CUSTOM ? roles.findCustomDeptIds(role.getId()) : List.of();
            grants.add(new RoleGrant(role.getDataScope(), role.getStatus(), custom));
        }
        return resolver.resolve(user.getId(), user.getDeptId(), path, user.isPlatformAdmin(), grants);
    }

    /**
     * 用户不在范围内时当作不存在。
     *
     * @param target 目标用户，允许为 null
     * @param scope  当前范围
     */
    public void assertUserVisible(User target, DataScopeSpec scope) {
        if (target == null || !userVisible(target, scope)) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
    }

    /**
     * 应用不在范围内时当作不存在。仅本人按创建人，其余按应用所属部门。
     *
     * @param target 目标应用
     * @param scope  当前范围
     */
    public void assertAppVisible(App target, DataScopeSpec scope) {
        if (target == null || !appVisible(target, scope)) {
            throw new BizException(ErrorCode.NOT_FOUND, "应用不存在");
        }
    }

    /**
     * @param dept  部门
     * @param scope 当前范围
     * @return 是否出现在部门树中
     */
    public boolean deptVisible(Dept dept, DataScopeSpec scope) {
        if (scope.isUnrestricted()) {
            return true;
        }
        if (scope.isIncludeSelf() && dept.getId() != null && dept.getId().equals(scope.getAnchorDeptId())) {
            return true;
        }
        if (dept.getId() != null && scope.getDeptIds().contains(dept.getId())) {
            return true;
        }
        // path 为 0,1 这种逗号串，按层级前缀判定，避免 0,1 命中 0,12
        for (String prefix : scope.getPathPrefixes()) {
            if (DeptPath.covers(prefix, dept.getPath())) {
                return true;
            }
        }
        return false;
    }

    private boolean userVisible(User target, DataScopeSpec scope) {
        if (scope.isUnrestricted()) {
            return true;
        }
        if (scope.isIncludeSelf() && target.getId().equals(scope.getSelfUserId())) {
            return true;
        }
        if (target.getDeptId() != null && scope.getDeptIds().contains(target.getDeptId())) {
            return true;
        }
        String path = target.getDeptPath();
        if (path == null && target.getDeptId() != null) {
            path = depts.findById(target.getDeptId()).map(Dept::getPath).orElse(null);
        }
        for (String prefix : scope.getPathPrefixes()) {
            if (DeptPath.covers(prefix, path)) {
                return true;
            }
        }
        return false;
    }

    private boolean appVisible(App target, DataScopeSpec scope) {
        if (scope.isUnrestricted()) {
            return true;
        }
        if (scope.isIncludeSelf() && target.getCreatedBy() != null && target.getCreatedBy().equals(scope.getSelfUserId())) {
            return true;
        }
        if (target.getDeptId() != null && scope.getDeptIds().contains(target.getDeptId())) {
            return true;
        }
        if (target.getDeptId() == null) {
            return false;
        }
        String path = depts.findById(target.getDeptId()).map(Dept::getPath).orElse(null);
        if (path == null) {
            return false;
        }
        for (String prefix : scope.getPathPrefixes()) {
            if (DeptPath.covers(prefix, path)) {
                return true;
            }
        }
        return false;
    }
}
