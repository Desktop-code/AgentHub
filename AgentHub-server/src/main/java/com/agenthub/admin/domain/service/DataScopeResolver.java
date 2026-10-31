package com.agenthub.admin.domain.service;

import com.agenthub.admin.domain.enums.DataScope;
import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.admin.domain.model.DataScopeSpec;
import com.agenthub.admin.domain.model.RoleGrant;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把多个角色的数据范围收成一份查询条件。所属域：admin。
 * 约束：只做并集，不访问数据库；「全部」或超管直接放开。
 */
@Component
public class DataScopeResolver {

    /**
     * 合并当前用户的数据范围。
     *
     * @param userId         操作者 id
     * @param deptId         操作者部门，可空
     * @param deptPath       操作者部门 path，可空
     * @param platformAdmin  sys_user.is_admin = 1 时为 true，跳过角色范围
     * @param grants         用户拥有的角色，含停用角色
     * @return 给仓储使用的范围；无任何有效范围时退化为仅本人，避免查出全表
     */
    public DataScopeSpec resolve(Long userId, Long deptId, String deptPath, boolean platformAdmin, List<RoleGrant> grants) {
        DataScopeSpec spec = new DataScopeSpec();
        spec.setSelfUserId(userId);
        spec.setAnchorDeptId(deptId);
        if (platformAdmin) {
            spec.setUnrestricted(true);
            return spec;
        }
        if (grants != null) {
            for (RoleGrant grant : grants) {
                if (!RecordStatus.isEnabled(grant.getStatus()) || grant.getDataScope() == null) {
                    continue;
                }
                // 任一角色是全部，就不再叠加更窄的条件
                if (grant.getDataScope() == DataScope.ALL) {
                    spec.setUnrestricted(true);
                    return spec;
                }
                apply(spec, grant, deptId, deptPath);
            }
        }
        if (!spec.isIncludeSelf() && spec.getDeptIds().isEmpty() && spec.getPathPrefixes().isEmpty()) {
            spec.setIncludeSelf(true);
        }
        return spec;
    }

    /**
     * 单条角色并入范围。自定义部门只取关联表里的 id，不自动展开子部门。
     */
    private void apply(DataScopeSpec spec, RoleGrant grant, Long deptId, String deptPath) {
        switch (grant.getDataScope()) {
            case SELF -> spec.setIncludeSelf(true);
            case DEPT -> {
                if (deptId != null) {
                    spec.getDeptIds().add(deptId);
                }
            }
            case DEPT_AND_CHILD -> {
                if (deptPath != null && !deptPath.isBlank()) {
                    spec.getPathPrefixes().add(deptPath);
                }
            }
            case CUSTOM -> {
                if (grant.getCustomDeptIds() != null) {
                    spec.getDeptIds().addAll(grant.getCustomDeptIds());
                }
            }
            case ALL -> spec.setUnrestricted(true);
            default -> spec.setIncludeSelf(true);
        }
    }
}
