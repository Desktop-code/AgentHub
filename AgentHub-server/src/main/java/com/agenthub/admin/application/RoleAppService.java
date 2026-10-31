package com.agenthub.admin.application;

import com.agenthub.admin.api.dto.RoleDataScopeReq;
import com.agenthub.admin.api.dto.RoleMenuReq;
import com.agenthub.admin.api.dto.RolePageReq;
import com.agenthub.admin.api.dto.RoleResp;
import com.agenthub.admin.api.dto.RoleSaveReq;
import com.agenthub.admin.domain.enums.DataScope;
import com.agenthub.admin.domain.enums.PermissionCodes;
import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.admin.domain.model.Menu;
import com.agenthub.admin.domain.model.Role;
import com.agenthub.admin.domain.model.RoleQuery;
import com.agenthub.admin.domain.repository.DeptRepository;
import com.agenthub.admin.domain.repository.MenuRepository;
import com.agenthub.admin.domain.repository.RoleRepository;
import com.agenthub.admin.domain.service.MenuTreeBuilder;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;

/**
 * 角色、菜单授权和数据范围。所属域：admin。admin 角色不能删除或停用。
 */
@Service
public class RoleAppService {

    private final RoleRepository roles;
    private final MenuRepository menus;
    private final DeptRepository depts;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;

    /**
     * @param roles           角色仓储
     * @param menus           校验并补齐菜单祖先
     * @param depts           校验自定义部门
     * @param operatorContext 操作者
     * @param operLogWriter   操作日志
     */
    public RoleAppService(RoleRepository roles, MenuRepository menus, DeptRepository depts,
                          OperatorContext operatorContext, OperLogWriter operLogWriter) {
        this.roles = roles;
        this.menus = menus;
        this.depts = depts;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
    }

    /**
     * @param req 分页条件
     * @return 角色页，不含菜单明细
     */
    public PageResult<RoleResp> page(RolePageReq req) {
        RoleQuery query = new RoleQuery();
        query.setCurrent(req.getCurrent());
        query.setSize(req.getSize());
        query.setCode(LikeTexts.contains(req.getCode()));
        query.setName(LikeTexts.contains(req.getName()));
        query.setStatus(req.getStatus());
        PageResult<Role> page = roles.page(query);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords().stream().map(this::toResp).toList());
    }

    /**
     * @param id 角色 id
     * @return 含菜单和自定义部门，供编辑弹窗回显
     */
    public RoleResp get(Long id) {
        Role role = required(id);
        role.setMenuIds(roles.findMenuIds(id));
        role.setCustomDeptIds(roles.findCustomDeptIds(id));
        return toResp(role);
    }

    /**
     * @param req 新角色
     * @return 创建结果
     * @throws BizException 编码或名称占用、数据范围无法识别
     */
    @Transactional
    public RoleResp create(RoleSaveReq req) {
        if (roles.codeTaken(req.getCode())) {
            throw new BizException(ErrorCode.DUPLICATE, "角色编码已存在（含已删除角色）");
        }
        String name = req.getName().trim();
        if (roles.nameTaken(name, null)) {
            throw new BizException(ErrorCode.DUPLICATE, "角色名称已存在");
        }
        Role role = new Role();
        role.setCode(req.getCode().trim());
        fill(role, req);
        Long id = roles.insert(role);
        operLogWriter.write("role", "create", "role", String.valueOf(id), "创建角色 " + role.getCode());
        return get(id);
    }

    /**
     * 不修改编码。admin 角色不能停用；名称不能与其它未删除角色重复。
     *
     * @param id  角色 id
     * @param req 名称、范围和状态
     * @return 更新后的角色
     * @throws BizException 名称占用或试图停用管理员角色
     */
    @Transactional
    public RoleResp update(Long id, RoleSaveReq req) {
        Role role = required(id);
        if (PermissionCodes.ADMIN_ROLE.equals(role.getCode()) && !RecordStatus.isEnabled(req.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能停用管理员角色");
        }
        // 名称唯一：仅在名称真的被改动时校验，兼容历史重复数据
        String newName = req.getName().trim();
        if (!newName.equals(role.getName()) && roles.nameTaken(newName, id)) {
            throw new BizException(ErrorCode.DUPLICATE, "角色名称已存在");
        }
        fill(role, req);
        roles.update(role);
        operLogWriter.write("role", "update", "role", String.valueOf(id), "更新角色 " + role.getCode());
        return get(id);
    }

    /**
     * @param id 角色 id
     * @throws BizException 试图删除 admin 角色
     */
    @Transactional
    public void delete(Long id) {
        Role role = required(id);
        if (PermissionCodes.ADMIN_ROLE.equals(role.getCode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能删除管理员角色");
        }
        roles.logicDelete(id, operatorContext.requireUserId());
        operLogWriter.write("role", "delete", "role", String.valueOf(id), "删除角色 " + role.getCode());
    }

    /**
     * 覆盖菜单，并自动带上祖先，保证侧栏能组树。
     *
     * @param id  角色 id
     * @param req 勾选的菜单
     */
    @Transactional
    public void assignMenus(Long id, RoleMenuReq req) {
        Role role = required(id);
        List<Menu> all = menus.listAll();
        for (Long menuId : req.getMenuIds()) {
            if (all.stream().noneMatch(menu -> menu.getId().equals(menuId))) {
                throw new BizException(ErrorCode.BAD_REQUEST, "菜单不存在");
            }
        }
        roles.replaceMenus(id, MenuTreeBuilder.withAncestors(new HashSet<>(req.getMenuIds()), all));
        operLogWriter.write("role", "grant", "role", String.valueOf(id), "分配菜单 " + role.getCode());
    }

    /**
     * 非自定义范围会清空部门白名单，避免残留条件以后被误用。
     *
     * @param id  角色 id
     * @param req 范围和部门
     */
    @Transactional
    public void updateDataScope(Long id, RoleDataScopeReq req) {
        Role role = required(id);
        DataScope scope = dataScope(req.getDataScope());
        role.setDataScope(scope);
        roles.update(role);
        if (scope == DataScope.CUSTOM) {
            if (req.getDeptIds() != null) {
                for (Long deptId : req.getDeptIds()) {
                    if (depts.findById(deptId).isEmpty()) {
                        throw new BizException(ErrorCode.BAD_REQUEST, "部门不存在");
                    }
                }
            }
            roles.replaceCustomDepts(id, req.getDeptIds());
        } else {
            roles.replaceCustomDepts(id, List.of());
        }
        operLogWriter.write("role", "update", "role", String.valueOf(id), "调整数据范围 " + role.getCode());
    }

    private Role required(Long id) {
        return roles.findById(id).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "角色不存在"));
    }

    private void fill(Role role, RoleSaveReq req) {
        role.setName(req.getName().trim());
        role.setDataScope(dataScope(req.getDataScope()));
        role.setSort(req.getSort() == null ? 0 : req.getSort());
        role.setStatus(RecordStatus.isEnabled(req.getStatus()) ? 1 : 0);
        role.setRemark(req.getRemark());
    }

    private DataScope dataScope(Integer code) {
        DataScope scope = DataScope.of(code);
        if (scope == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "数据范围不正确");
        }
        return scope;
    }

    private RoleResp toResp(Role role) {
        RoleResp resp = new RoleResp();
        resp.setId(role.getId());
        resp.setCode(role.getCode());
        resp.setName(role.getName());
        resp.setDataScope(role.getDataScope() == null ? null : role.getDataScope().getCode());
        resp.setDataScopeLabel(role.getDataScope() == null ? null : role.getDataScope().getLabel());
        resp.setSort(role.getSort());
        resp.setStatus(role.getStatus());
        resp.setRemark(role.getRemark());
        resp.setCreateTime(role.getCreateTime());
        resp.setMenuIds(role.getMenuIds());
        resp.setCustomDeptIds(role.getCustomDeptIds());
        return resp;
    }
}
