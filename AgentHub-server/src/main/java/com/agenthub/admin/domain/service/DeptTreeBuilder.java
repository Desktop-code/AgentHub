package com.agenthub.admin.domain.service;

import com.agenthub.admin.domain.model.Dept;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把扁平部门组装成树。所属域：admin。父节点不在本次结果里时，该节点作为森林的根，避免断枝。
 */
public final class DeptTreeBuilder {

    private DeptTreeBuilder() {
    }

    /**
     * @param flat 未删除部门，方法会清空并重填 children
     * @return 根节点列表，按 sort、id 排序
     */
    public static List<Dept> build(List<Dept> flat) {
        Map<Long, Dept> byId = new HashMap<>();
        for (Dept dept : flat) {
            dept.setChildren(new ArrayList<>());
            byId.put(dept.getId(), dept);
        }
        List<Dept> roots = new ArrayList<>();
        for (Dept dept : flat) {
            Dept parent = dept.getParentId() == null ? null : byId.get(dept.getParentId());
            if (parent == null || dept.getParentId() == 0) {
                roots.add(dept);
            } else {
                parent.getChildren().add(dept);
            }
        }
        sort(roots);
        return roots;
    }

    private static void sort(List<Dept> nodes) {
        nodes.sort(Comparator.comparing((Dept d) -> d.getSort() == null ? 0 : d.getSort()).thenComparing(Dept::getId));
        for (Dept node : nodes) {
            sort(node.getChildren());
        }
    }
}
