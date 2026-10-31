package com.agenthub.admin.application;

import com.agenthub.admin.api.dto.MenuResp;
import com.agenthub.admin.api.dto.MenuSaveReq;
import com.agenthub.admin.domain.enums.MenuType;
import com.agenthub.admin.domain.model.Menu;
import com.agenthub.admin.domain.repository.MenuRepository;
import com.agenthub.admin.domain.service.MenuTreeBuilder;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜单维护。所属域：admin。按钮必须有权限码；有子节点时不能删。
 */
@Service
public class MenuAppService {

    private final MenuRepository menus;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;

    /**
     * @param menus           菜单仓储
     * @param operatorContext 操作者
     * @param operLogWriter   操作日志
     */
    public MenuAppService(MenuRepository menus, OperatorContext operatorContext, OperLogWriter operLogWriter) {
        this.menus = menus;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
    }

    /**
     * @return 含按钮的完整菜单树
     */
    public List<MenuResp> tree() {
        return toResps(MenuTreeBuilder.build(menus.listAll()));
    }

    /**
     * @param req 新菜单
     * @return 保存后的节点，不含子节点
     */
    @Transactional
    public MenuResp create(MenuSaveReq req) {
        assertParent(null, req.getParentId());
        assertButtonPermission(req);
        Long id = menus.insert(toModel(null, req));
        operLogWriter.write("menu", "create", "menu", String.valueOf(id), "创建菜单 " + req.getName());
        return toResp(menus.findById(id).orElseThrow());
    }

    /**
     * @param id  菜单 id
     * @param req 新资料
     * @return 更新后的节点
     * @throws BizException 父节点形成环
     */
    @Transactional
    public MenuResp update(Long id, MenuSaveReq req) {
        Menu existing = required(id);
        assertParent(id, req.getParentId());
        assertButtonPermission(req);
        menus.update(toModel(id, req));
        operLogWriter.write("menu", "update", "menu", String.valueOf(id), "更新菜单 " + existing.getName());
        return toResp(menus.findById(id).orElseThrow());
    }

    /**
     * @param id 菜单 id
     * @throws BizException 仍有子节点
     */
    @Transactional
    public void delete(Long id) {
        Menu existing = required(id);
        if (menus.hasChildren(id)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先删除子菜单");
        }
        menus.logicDelete(id, operatorContext.requireUserId());
        operLogWriter.write("menu", "delete", "menu", String.valueOf(id), "删除菜单 " + existing.getName());
    }

    /**
     * 父节点向上走如果碰到自己，就是把节点挂到子孙下面。
     */
    private void assertParent(Long selfId, Long parentId) {
        if (parentId == null || parentId == 0) {
            return;
        }
        if (parentId.equals(selfId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能把菜单挂到自己下面");
        }
        Long cursor = parentId;
        int guard = 0;
        while (cursor != null && cursor != 0 && guard++ < 30) {
            if (cursor.equals(selfId)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "不能把菜单移动到自己的下级");
            }
            Menu parent = menus.findById(cursor).orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "父菜单不存在"));
            cursor = parent.getParentId();
        }
    }

    /**
     * 校验类型取值；按钮必须带权限码。目录与无折叠目录不强制路由/组件。
     */
    private void assertButtonPermission(MenuSaveReq req) {
        if (!MenuType.isValid(req.getType())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "菜单类型不正确");
        }
        if (req.getType() == MenuType.BUTTON.getCode()
                && (req.getPermission() == null || req.getPermission().isBlank())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "按钮必须填写权限码");
        }
    }

    private Menu required(Long id) {
        return menus.findById(id).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "菜单不存在"));
    }

    /**
     * 目录与目录（无折叠）入库字段规则相同，仅 type 不同；侧栏按 type 决定是否折叠。
     */
    private Menu toModel(Long id, MenuSaveReq req) {
        Menu menu = new Menu();
        menu.setId(id);
        menu.setParentId(req.getParentId());
        menu.setType(req.getType());
        menu.setName(req.getName().trim());
        menu.setPath(req.getPath());
        menu.setComponent(req.getComponent());
        menu.setRedirect(req.getRedirect());
        menu.setPermission(req.getPermission());
        menu.setIcon(req.getIcon());
        menu.setSort(req.getSort());
        menu.setVisible(req.getVisible());
        menu.setStatus(req.getStatus());
        menu.setOpenType(req.getOpenType());
        return menu;
    }

    private List<MenuResp> toResps(List<Menu> menus) {
        List<MenuResp> list = new ArrayList<>();
        for (Menu menu : menus) {
            MenuResp resp = toResp(menu);
            resp.setChildren(toResps(menu.getChildren()));
            list.add(resp);
        }
        return list;
    }

    private MenuResp toResp(Menu menu) {
        MenuResp resp = new MenuResp();
        resp.setId(menu.getId());
        resp.setParentId(menu.getParentId());
        resp.setType(menu.getType());
        resp.setName(menu.getName());
        resp.setPath(menu.getPath());
        resp.setComponent(menu.getComponent());
        resp.setRedirect(menu.getRedirect());
        resp.setPermission(menu.getPermission());
        resp.setIcon(menu.getIcon());
        resp.setSort(menu.getSort());
        resp.setVisible(menu.getVisible());
        resp.setStatus(menu.getStatus());
        resp.setOpenType(menu.getOpenType());
        return resp;
    }
}
