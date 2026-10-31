package com.agenthub.auth.application;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.admin.domain.model.Menu;
import com.agenthub.admin.domain.model.Role;
import com.agenthub.admin.domain.model.User;
import com.agenthub.admin.domain.repository.MenuRepository;
import com.agenthub.admin.domain.repository.RoleRepository;
import com.agenthub.admin.domain.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 计算某个平台用户的角色编码、权限码和可见菜单。所属域：auth。
 * 超管拿全部启用中的权限码；普通用户只拿启用角色勾选的菜单。停用角色不参与。
 */
@Service
public class AccessQueryService {

    private final RoleRepository roles;
    private final MenuRepository menus;

    /**
     * @param roles 用户角色
     * @param menus 菜单与权限码
     */
    public AccessQueryService(RoleRepository roles, MenuRepository menus) {
        this.roles = roles;
        this.menus = menus;
    }

    /**
     * @param user 已登录的平台用户
     * @return 启用角色的编码
     */
    public List<String> roleCodes(User user) {
        return enabledRoles(user).stream().map(Role::getCode).filter(code -> code != null && !code.isBlank()).distinct().toList();
    }

    /**
     * @param user 已登录的平台用户
     * @return Sa-Token 要校验的权限码
     */
    public List<String> permissions(User user) {
        if (!RecordStatus.isEnabled(user.getStatus())) {
            return List.of();
        }
        if (user.isPlatformAdmin()) {
            return menus.listEnabledPermissionCodes();
        }
        return codes(visibleMenus(user));
    }

    /**
     * @param user 已登录的平台用户
     * @return 组导航树用的菜单，含目录和页面，调用方再去掉按钮
     */
    public List<Menu> visibleMenus(User user) {
        if (!RecordStatus.isEnabled(user.getStatus())) {
            return List.of();
        }
        if (user.isPlatformAdmin()) {
            return menus.listEnabled();
        }
        List<Long> roleIds = enabledRoles(user).stream().map(Role::getId).toList();
        return menus.listEnabledByRoleIds(roleIds);
    }

    private List<Role> enabledRoles(User user) {
        return roles.findByUserId(user.getId()).stream()
                .filter(role -> RecordStatus.isEnabled(role.getStatus()))
                .toList();
    }

    private List<String> codes(List<Menu> granted) {
        return granted.stream()
                .map(Menu::getPermission)
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .toList();
    }
}
