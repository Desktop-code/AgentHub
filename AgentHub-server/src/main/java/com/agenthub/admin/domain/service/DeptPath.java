package com.agenthub.admin.domain.service;

/**
 * 部门层级路径的拼装与前缀判定。所属域：admin。
 *
 * <p>格式：顶层从虚拟根 0 开始，用英文逗号连接自身及全部祖先主键，
 * 例如部门 1（顶级）为 0,1，其子部门 3 为 0,1,3。</p>
 *
 * <p>约束：只做字符串运算，不访问数据库；「本部门及以下」一律按
 * path 等于前缀、或 path 以前缀加逗号开头来判定，
 * 避免 0,1 误命中 0,12…；禁止使用 FIND_IN_SET。</p>
 */
public final class DeptPath {

    /** 虚拟根的固定值，库里不存在 id=0 的部门行 */
    public static final String ROOT = "0";

    /** 层级分隔符 */
    public static final String SEPARATOR = ",";

    private DeptPath() {
    }

    /**
     * 拼接子节点 path。
     *
     * @param parentPath 父节点 path，顶级传 {@link #ROOT}
     * @param id         子节点主键，必须非空
     * @return 形如 0,1,3 的路径
     */
    public static String ofChild(String parentPath, Long id) {
        return normalize(parentPath) + SEPARATOR + id;
    }

    /**
     * 顶层部门的 path。
     *
     * @param id 部门主键
     * @return 形如 0,1 的路径
     */
    public static String ofRoot(Long id) {
        return ROOT + SEPARATOR + id;
    }

    /**
     * 前缀是否覆盖目标节点（含自身）。用于「本部门及以下」可见性判断，
     * 调用方需保证两边都是本类生成的格式。
     *
     * @param prefix 前缀，形如 0,1
     * @param path   目标 path，形如 0,1,3
     * @return 目标属于该前缀子树时为 true
     */
    public static boolean covers(String prefix, String path) {
        if (prefix == null || prefix.isBlank() || path == null || path.isBlank()) {
            return false;
        }
        return path.equals(prefix) || path.startsWith(prefix + SEPARATOR);
    }

    /**
     * 空路径兜底成虚拟根，避免拼出以逗号开头的脏值。
     *
     * @param path 可能为空的路径
     * @return 非空的路径字符串
     */
    public static String normalize(String path) {
        return path == null || path.isBlank() ? ROOT : path.trim();
    }
}
