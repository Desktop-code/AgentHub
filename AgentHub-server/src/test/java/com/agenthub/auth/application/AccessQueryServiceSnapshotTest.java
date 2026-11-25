package com.agenthub.auth.application;

import com.agenthub.admin.domain.model.Menu;
import com.agenthub.admin.domain.model.Role;
import com.agenthub.admin.domain.model.User;
import com.agenthub.admin.domain.repository.MenuRepository;
import com.agenthub.admin.domain.repository.RoleRepository;
import com.agenthub.auth.domain.model.AccessSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 访问快照的直读语义：必须与改造前三个独立方法逐条一致。所属域：auth。
 * <p>
 * 这层是缓存旁路的真值来源，一旦算错，缓存命中与否都会错；因此用例覆盖
 * 「超管 / 普通用户 / 停用用户 / 停用角色」四条分支，以及按钮不进导航树。
 */
class AccessQueryServiceSnapshotTest {

    private final RoleRepository roles = mock(RoleRepository.class);
    private final MenuRepository menus = mock(MenuRepository.class);
    private final AccessQueryService service = new AccessQueryService(roles, menus);

    private static User user(Long id, boolean admin, int status) {
        User user = new User();
        user.setId(id);
        user.setUsername("u" + id);
        user.setStatus(status);
        user.setPlatformAdmin(admin);
        return user;
    }

    private static Role role(Long id, String code, int status) {
        Role role = new Role();
        role.setId(id);
        role.setCode(code);
        role.setName(code);
        role.setStatus(status);
        return role;
    }

    private static Menu menu(Long id, Long parentId, int type, String permission) {
        Menu menu = new Menu();
        menu.setId(id);
        menu.setParentId(parentId);
        menu.setName("m" + id);
        menu.setType(type);
        menu.setPermission(permission);
        menu.setSort(0);
        menu.setStatus(1);
        return menu;
    }

    /**
     * 超管：读全部启用菜单；权限码由菜单派生（与旧 listEnabledPermissionCodes 同源）；
     * 按钮只贡献权限码，不进导航树。
     */
    @Test
    void adminGetsAllEnabledMenusAndCodes() {
        when(roles.findByUserId(1L)).thenReturn(List.of(role(10L, "admin", 1)));
        when(menus.listEnabled()).thenReturn(List.of(
                menu(100L, 0L, 1, null),
                menu(101L, 100L, 2, "tool:list"),
                menu(102L, 101L, 3, "tool:create")));
        when(menus.listEnabledByRoleIds(anyList())).thenReturn(List.of());

        AccessSnapshot snapshot = service.snapshot(user(1L, true, 1));

        assertEquals(List.of("admin"), snapshot.getRoles());
        assertEquals(List.of("tool:list", "tool:create"), snapshot.getPermissions());
        // 顶层只剩目录；按钮被剔除
        assertEquals(1, snapshot.getMenus().size());
        assertEquals(List.of(101L), snapshot.getMenus().get(0).getChildren().stream().map(Menu::getId).toList());
        verify(menus, never()).listEnabledByRoleIds(anyList());
    }

    /**
     * 普通用户：按启用角色反查菜单；角色停用不参与。
     */
    @Test
    void normalUserUsesEnabledRoleMenusOnly() {
        when(roles.findByUserId(2L)).thenReturn(List.of(role(10L, "ops", 1), role(11L, "old", 0)));
        when(menus.listEnabledByRoleIds(List.of(10L))).thenReturn(List.of(
                menu(200L, 0L, 2, "agent:run")));

        AccessSnapshot snapshot = service.snapshot(user(2L, false, 1));

        assertEquals(List.of("ops"), snapshot.getRoles());
        assertEquals(List.of("agent:run"), snapshot.getPermissions());
        assertEquals(1, snapshot.getMenus().size());
        // 只把启用角色的 id 交给仓储，停用角色不得参与授权
        verify(menus).listEnabledByRoleIds(List.of(10L));
    }

    /**
     * 停用用户：角色照旧返回（旧 roleCodes 不看启停），权限与菜单必须为空且不查菜单表。
     */
    @Test
    void disabledUserHasNoPermissionsAndNoMenuQuery() {
        when(roles.findByUserId(3L)).thenReturn(List.of(role(10L, "ops", 1)));

        AccessSnapshot snapshot = service.snapshot(user(3L, false, 0));

        assertEquals(List.of("ops"), snapshot.getRoles());
        assertTrue(snapshot.getPermissions().isEmpty());
        assertTrue(snapshot.getMenus().isEmpty());
        verify(menus, never()).listEnabled();
        verify(menus, never()).listEnabledByRoleIds(anyList());
    }

    /**
     * 空用户与空主键不查库，直接返回空快照（null 安全）。
     */
    @Test
    void nullUserYieldsEmptySnapshot() {
        assertTrue(service.snapshot(null).getPermissions().isEmpty());
        assertTrue(service.snapshot(user(null, false, 1)).getMenus().isEmpty());
        verify(roles, never()).findByUserId(anyLong());
    }

    /**
     * 没有角色的普通用户：菜单与权限都为空，但不会抛异常。
     */
    @Test
    void userWithoutRolesGetsEmptyAccess() {
        when(roles.findByUserId(4L)).thenReturn(List.of());

        AccessSnapshot snapshot = service.snapshot(user(4L, false, 1));

        assertTrue(snapshot.getRoles().isEmpty());
        assertTrue(snapshot.getPermissions().isEmpty());
        assertTrue(snapshot.getMenus().isEmpty());
    }
}
