package com.agenthub.agent.api;

/**
 * 多智能体方案权限码。所属域：agent。
 *
 * <p>本刀（MA-P2 刀1）起，ma_def 是独立实体，权限码与旧 {@code agent:route:view}
 * 分离，避免「码叫 route、实体叫 ma」的长期漂移：
 * <ul>
 *   <li>{@link #VIEW}：列表 / 详情 / 按根查绑定；</li>
 *   <li>{@link #EDIT}：新建 / 更新 / 启停 / 删除；</li>
 *   <li>{@link #RUN}：试跑占位码——本刀试跑仍走旧 Agent 试跑权，暂不在接口上强制。</li>
 * </ul>
 * 菜单种子、接口注解、前端按钮必须同码。
 */
public final class MaPermissions {

    public static final String VIEW = "ma:def:view";
    public static final String EDIT = "ma:def:edit";
    public static final String RUN = "ma:def:run";

    private MaPermissions() {
    }
}
