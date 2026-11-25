package com.agenthub.admin.domain.service;

import com.agenthub.admin.domain.enums.DataScope;
import com.agenthub.admin.domain.model.DataScopeSpec;
import com.agenthub.admin.domain.model.RoleGrant;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据范围并集。不连数据库。
 */
class DataScopeResolverTest {

    private final DataScopeResolver resolver = new DataScopeResolver();

    /**
     * 超管不再看角色上的窄范围。
     */
    @Test
    void platformAdminIsUnrestricted() {
        DataScopeSpec spec = resolver.resolve(1L, 8L, "0,1,8", true,
                List.of(new RoleGrant(DataScope.SELF, 1, List.of())));
        assertTrue(spec.isUnrestricted());
    }

    /**
     * 任一角色为全部时，其他角色的部门条件作废。
     */
    @Test
    void anyAllRoleOpensScope() {
        DataScopeSpec spec = resolver.resolve(1L, 8L, "0,1,8", false, List.of(
                new RoleGrant(DataScope.DEPT, 1, List.of()),
                new RoleGrant(DataScope.ALL, 1, List.of())));
        assertTrue(spec.isUnrestricted());
    }

    /**
     * 本部门、下级前缀和仅本人可以同时成立。停用角色不参与。
     */
    @Test
    void mergesEnabledRolesAndIgnoresDisabled() {
        DataScopeSpec spec = resolver.resolve(9L, 8L, "0,1,8", false, List.of(
                new RoleGrant(DataScope.SELF, 1, List.of()),
                new RoleGrant(DataScope.DEPT_AND_CHILD, 1, List.of()),
                new RoleGrant(DataScope.CUSTOM, 0, List.of(3L))));
        assertFalse(spec.isUnrestricted());
        assertTrue(spec.isIncludeSelf());
        assertEquals(9L, spec.getSelfUserId());
        assertEquals(List.of("0,1,8"), spec.getPathPrefixes());
        assertTrue(spec.getDeptIds().isEmpty());
    }

    /**
     * 没有任何有效角色时退化为仅本人，避免查出全表。
     */
    @Test
    void emptyGrantsFallBackToSelf() {
        DataScopeSpec spec = resolver.resolve(4L, null, null, false, List.of());
        assertTrue(spec.isIncludeSelf());
        assertFalse(spec.isUnrestricted());
    }
}
