package com.agenthub.admin.domain.service;

import com.agenthub.admin.domain.enums.MenuType;
import com.agenthub.admin.domain.model.Menu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 菜单树与祖先补齐。所属域：admin。
 */
public final class MenuTreeBuilder {

    private MenuTreeBuilder() {
    }

    /**
     * 组导航树。按钮节点不进入结果，避免侧栏出现权限码。
     *
     * @param flat 已按权限过滤的菜单，可含按钮
     * @return 目录和页面组成的树
     */
    public static List<Menu> buildNavigable(List<Menu> flat) {
        List<Menu> pages = flat.stream()
                .filter(menu -> menu.getType() == null || menu.getType() != MenuType.BUTTON.getCode())
                .toList();
        return build(pages);
    }

    /**
     * 管理端需要看到按钮，所以保留全部类型。
     *
     * @param flat 未删除菜单
     * @return 完整树
     */
    public static List<Menu> build(List<Menu> flat) {
        Map<Long, Menu> byId = new HashMap<>();
        for (Menu menu : flat) {
            menu.setChildren(new ArrayList<>());
            byId.put(menu.getId(), menu);
        }
        List<Menu> roots = new ArrayList<>();
        for (Menu menu : flat) {
            Menu parent = menu.getParentId() == null ? null : byId.get(menu.getParentId());
            if (parent == null || menu.getParentId() == 0) {
                roots.add(menu);
            } else {
                parent.getChildren().add(menu);
            }
        }
        sort(roots);
        return roots;
    }

    /**
     * 只勾选按钮时补上目录和菜单，否则 /me 组不出侧栏。
     *
     * @param selected 前端提交的菜单 id
     * @param all      当前全部未删除菜单
     * @return 含祖先的 id
     */
    public static Set<Long> withAncestors(Set<Long> selected, List<Menu> all) {
        Map<Long, Menu> byId = new HashMap<>();
        for (Menu menu : all) {
            byId.put(menu.getId(), menu);
        }
        Set<Long> result = new HashSet<>();
        for (Long id : selected) {
            Long cursor = id;
            while (cursor != null && cursor != 0 && result.add(cursor)) {
                Menu menu = byId.get(cursor);
                if (menu == null) {
                    break;
                }
                cursor = menu.getParentId();
            }
        }
        return result;
    }

    private static void sort(List<Menu> nodes) {
        nodes.sort(Comparator.comparing((Menu m) -> m.getSort() == null ? 0 : m.getSort()).thenComparing(Menu::getId));
        for (Menu node : nodes) {
            sort(node.getChildren());
        }
    }
}
