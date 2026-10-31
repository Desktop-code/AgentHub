package com.agenthub.obs.application;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.admin.domain.model.Role;
import com.agenthub.admin.domain.model.User;
import com.agenthub.admin.domain.repository.RoleRepository;
import com.agenthub.admin.domain.repository.UserRepository;
import com.agenthub.common.web.OperatorContext;
import org.springframework.stereotype.Component;

/**
 * 调用追踪的可见范围。所属域：obs。
 * 平台超管或启用中的 admin 角色看全部；其它登录用户只看自己的 user_id。
 */
@Component
public class ObsAccess {

    private static final String ADMIN_ROLE = "admin";

    private final OperatorContext operatorContext;
    private final UserRepository users;
    private final RoleRepository roles;

    /**
     * @param operatorContext 当前登录人
     * @param users           是否平台超管
     * @param roles           是否持有 admin 角色
     */
    public ObsAccess(OperatorContext operatorContext, UserRepository users, RoleRepository roles) {
        this.operatorContext = operatorContext;
        this.users = users;
        this.roles = roles;
    }

    /**
     * @return 需要按人过滤时的用户 id；管理员为 null，表示不限 user_id
     */
    public Long ownerFilter() {
        long userId = operatorContext.requireUserId();
        return seesAll(userId) ? null : userId;
    }

    /**
     * @param userId 当前用户
     * @return 超管或 admin 角色为 true
     */
    public boolean seesAll(long userId) {
        User user = users.findById(userId).orElse(null);
        if (user != null && user.isPlatformAdmin()) {
            return true;
        }
        for (Role role : roles.findByUserId(userId)) {
            if (ADMIN_ROLE.equals(role.getCode()) && RecordStatus.isEnabled(role.getStatus())) {
                return true;
            }
        }
        return false;
    }
}
