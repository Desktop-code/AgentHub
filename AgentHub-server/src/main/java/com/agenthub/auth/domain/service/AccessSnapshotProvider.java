package com.agenthub.auth.domain.service;

import com.agenthub.auth.domain.model.AccessSnapshot;
import com.agenthub.admin.domain.model.User;

/**
 * 用户访问快照的读取口。所属域：auth。
 * <p>
 * 调用方：{@code StpInterfaceImpl}（每个 {@code @SaCheckPermission}）与 {@code AuthAppService.me}（侧栏菜单树）。
 * 实现方：
 * <ul>
 *   <li>{@code AccessQueryService}（bean 名 {@code dbAccessSnapshotProvider}）—— 直读多表，真值来源；</li>
 *   <li>{@code CachedAccessSnapshotProvider} —— {@code @Primary} 的 Redis 装饰器，生产走这条。</li>
 * </ul>
 * <p>
 * 语义约束：无论是否命中缓存，同一用户在同一 DB 状态下必须得到与直读完全一致的角色/权限/菜单。
 * 用户已停用时调用方仍会传进来，实现必须返回空权限与空菜单（与旧 {@code permissions}/{@code visibleMenus} 一致）。
 */
public interface AccessSnapshotProvider {

    /**
     * @param user 当前登录用户（调用方已按主键读库，含启停与是否超管）；可空
     * @return 角色、权限、导航树；用户为空时返回空快照
     */
    AccessSnapshot snapshot(User user);
}
