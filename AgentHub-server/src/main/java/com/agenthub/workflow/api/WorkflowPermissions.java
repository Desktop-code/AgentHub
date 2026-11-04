package com.agenthub.workflow.api;

/**
 * 工作流权限码。所属域：workflow。
 * <p>与 sql/temp/data-init-workflow-menu.sql、sql/temp/data-init-workflow-run-perm.sql、
 * 接口注解、前端按钮同码：
 * <ul>
 *   <li>view：查看列表 / 详情 / 草稿 / 版本</li>
 *   <li>edit：新建 / 改名 / 保存草稿</li>
 *   <li>publish：发布版本</li>
 *   <li>run:trial：发起试跑 / 查看 run / 取消 run</li>
 *   <li>run:invoke：应用触发（P1，本刀只挂码与灰显按钮，不做 sys_app 接线）</li>
 *   <li>human:act：处理人工待办 + 待办中心列表</li>
 * </ul>
 */
public final class WorkflowPermissions {

    /** 查看工作流 */
    public static final String DEF_VIEW = "workflow:def:view";
    /** 新建 / 改名 / 保存草稿 */
    public static final String DEF_EDIT = "workflow:def:edit";
    /** 发布版本 */
    public static final String DEF_PUBLISH = "workflow:def:publish";
    /** 试跑 / 查看 run / 取消 */
    public static final String RUN_TRIAL = "workflow:run:trial";
    /**
     * 应用触发（P1）：本刀只落码与灰显按钮，<b>不做</b> sys_app / apiKey 真接线。
     */
    public static final String RUN_INVOKE = "workflow:run:invoke";
    /** 处理人工待办（含待办中心列表） */
    public static final String HUMAN_ACT = "workflow:human:act";

    private WorkflowPermissions() {
    }
}
