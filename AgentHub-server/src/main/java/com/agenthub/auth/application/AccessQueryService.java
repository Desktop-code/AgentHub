package com.agenthub.auth.application;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.admin.domain.model.Menu;
import com.agenthub.admin.domain.model.Role;
import com.agenthub.admin.domain.model.User;
import com.agenthub.admin.domain.repository.MenuRepository;
import com.agenthub.admin.domain.repository.RoleRepository;
import com.agenthub.admin.domain.service.MenuTreeBuilder;
import com.agenthub.auth.domain.model.AccessSnapshot;
import com.agenthub.auth.domain.service.AccessSnapshotProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 计算某个平台用户的角色编码、权限码和可见菜单。所属域：auth。
 * 超管拿全部启用中的权限码与菜单；普通用户只拿启用角色勾选的菜单。停用角色不参与。
 * <p>
 * bean 名固定为 {@code dbAccessSnapshotProvider}：它是**真值来源**，被
 * {@code CachedAccessSnapshotProvider}（{@code @Primary}）用 {@code @Qualifier} 明确注入，
 * 避免装饰器把自身注入成委托对象。
 * <p>
 * 只暴露一个 {@link #snapshot(User)}：角色、权限、菜单必须**同一次计算**产出。
 * 历史上这里是三个独立方法，各自多查一次 {@code sys_user_role}；合并后既少一次查询，
 * 也保证缓存里三者永远同版本，不会出现「新权限配旧菜单」。
 */
@Service("dbAccessSnapshotProvider")
public class AccessQueryService implements AccessSnapshotProvider {

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
     * {@inheritDoc}
     * <p>
     * 与改造前三处计算逐条对齐：
     * <ol>
     *   <li>角色：启用角色编码，去重去空，<b>不看用户启停</b>（旧 {@code roleCodes} 即如此）；</li>
     *   <li>权限：用户停用 → 空；超管 → 全部启用菜单的权限码；普通用户 → 其可见菜单的权限码；</li>
     *   <li>菜单：用户停用 → 空；超管 → 全部启用菜单；普通用户 → 角色勾选且启用的菜单，再组树并剔除按钮。</li>
     * </ol>
     * 超管的权限码由「全部启用菜单」派生，与旧实现 {@code listEnabledPermissionCodes()} 的数据同源同结果
     * （后者就是 {@code listEnabled()} 上取非空 permission 去重），因此不额外多查一次。
     */
    @Override
    public AccessSnapshot snapshot(User user) {
        if (user == null || user.getId() == null) {
            return AccessSnapshot.empty();
        }
        List<Role> enabled = enabledRoles(user);
        List<String> roleCodes = enabled.stream()
                .map(Role::getCode)
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .toList();
        // 停用用户：权限与菜单都必须立即归零，不能靠缓存 TTL 等待
        if (!RecordStatus.isEnabled(user.getStatus())) {
            return new AccessSnapshot(user.getId(), roleCodes, List.of(), List.of());
        }
        // 超管读全量启用菜单；普通用户按启用角色反查。列表为空时仓储直接返回空，不打库
        List<Menu> granted = user.isPlatformAdmin()
                ? menus.listEnabled()
                : menus.listEnabledByRoleIds(enabled.stream().map(Role::getId).toList());
        List<String> permissions = granted.stream()
                .map(Menu::getPermission)
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .toList();
        // 组树会清空并重填 children，必须在过滤按钮之后做，结果才是侧栏可直接渲染的树
        return new AccessSnapshot(user.getId(), roleCodes, permissions, MenuTreeBuilder.buildNavigable(granted));
    }

    /**
     * 用户关联的启用角色。停用角色不参与权限与菜单，但用户本身停用与否不在这里判断。
     *
     * @param user 已登录用户
     * @return 启用角色
     */
    private List<Role> enabledRoles(User user) {
        return roles.findByUserId(user.getId()).stream()
                .filter(role -> RecordStatus.isEnabled(role.getStatus()))
                .toList();
    }
}
