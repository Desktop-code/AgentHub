package com.agenthub.rag.application;

import com.agenthub.common.web.OperatorContext;
import org.springframework.stereotype.Component;

/**
 * 平台管理员判定。所属域：rag。
 * <p>
 * 用途只有一个：PRIVATE 知识库对「非创建人」是否可见。
 * 刻意不用部门数据范围——知识库是配置态资产，按「谁能进知识库管理页」判断更直观，也不会因为部门调整而误放行。
 * <p>
 * 实现走 Sa-Token 的权限码判断；本组件是 rag 域内唯一碰鉴权的地方，
 * 便于测试时替换成桩（单测直接 new 一个固定返回值的子类或 mock）。
 */
@Component
public class RagAdminJudge {

    private final OperatorContext operatorContext;

    /**
     * @param operatorContext 操作人
     */
    public RagAdminJudge(OperatorContext operatorContext) {
        this.operatorContext = operatorContext;
    }

    /**
     * 当前登录人是否能看全部知识库。
     * <p>
     * 判据：持有知识库管理权限 {@code rag:kb:edit}。平台管理员默认拥有全部权限码，
     * 因此无需单独判 is_admin 位——权限码本身就是授权结果。
     *
     * @return true 表示可见全部
     */
    public boolean isPrivileged() {
        return operatorContext.userIdOrNull() != null && hasAnyKbManagePermission();
    }

    /**
     * 实际权限判断。Sa-Token 的 {@code StpUtil.hasPermission} 在未登录时会抛异常，
     * 因此先判登录（由调用方保证），这里只做权限码判断。
     *
     * @return true 表示具备任一知识库管理权限
     */
    boolean hasAnyKbManagePermission() {
        try {
            return cn.dev33.satoken.stp.StpUtil.hasPermission("rag:kb:edit")
                    || cn.dev33.satoken.stp.StpUtil.hasPermission("rag:kb:add")
                    || cn.dev33.satoken.stp.StpUtil.hasPermission("rag:kb:list");
        } catch (RuntimeException e) {
            // 无 Web 上下文（如单测或后台线程）：按非管理员处理，宁严不松
            return false;
        }
    }
}
