package com.agenthub.admin.domain.repository;

import com.agenthub.admin.domain.model.Dept;

import java.util.List;
import java.util.Optional;

/**
 * 部门仓储。所属域：admin。path 的改写单独成方法，避免和普通资料更新混在一次全字段写入里。
 * path 格式：逗号层级串，顶层从虚拟根 0 开始（如 0,1,3），规则见 DeptPath。
 */
public interface DeptRepository {

    /**
     * @return 全部未删除部门
     */
    List<Dept> listAll();

    /**
     * @param id 部门 id
     * @return 未删除部门
     */
    Optional<Dept> findById(Long id);

    /**
     * 插入时 path 可先为空，拿到主键后再 {@link #updatePath}。
     *
     * @param dept 新部门
     * @return 主键
     */
    Long insert(Dept dept);

    /**
     * 更新名称、排序、负责人和状态，不改 path 与 parent。
     *
     * @param dept 含 id 的部门
     */
    void updateProfile(Dept dept);

    /**
     * @param id   部门 id
     * @param path 新的绝对路径，形如 0,1,3
     */
    void updatePath(Long id, String path);

    /**
     * 把本部门及 path 下级一起改写到新前缀，并改本节点的 parent。
     *
     * @param id         被移动的部门
     * @param parentId   新父 id，根为 0
     * @param oldPrefix  移动前的 path
     * @param newPrefix  移动后的 path
     * @param operatorId 操作者
     */
    void rewritePath(Long id, Long parentId, String oldPrefix, String newPrefix, Long operatorId);

    /**
     * @param id         部门 id
     * @param operatorId 操作者
     */
    void logicDelete(Long id, Long operatorId);

    /**
     * @param id 部门 id
     * @return 有未删除子部门时为 true
     */
    boolean hasChildren(Long id);

    /**
     * @param id 部门 id
     * @return 有未删除用户挂在该部门时为 true
     */
    boolean hasUsers(Long id);
}
