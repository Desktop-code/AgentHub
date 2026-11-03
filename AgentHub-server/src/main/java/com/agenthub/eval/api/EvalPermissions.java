package com.agenthub.eval.api;

/**
 * 评测权限码。所属域：eval。
 * <p>与定稿 §5.4 一致，分两组：
 * <ul>
 *   <li>用例库：{@link #SUITE_VIEW} 看套件与用例、{@link #SUITE_EDIT} 建/改/导入/删（刀1）；</li>
 *   <li>跑批：{@link #RUN_TRIGGER} 建跑、{@link #RUN_VIEW} 看跑批与结果、{@link #RUN_CANCEL} 取消（刀2a）。</li>
 * </ul>
 * <p>跑批与用例库刻意分开授权：允许「只看结果」的角色（如测试同学）不必同时拿到建跑权。
 * <p>菜单种子见 {@code sql/temp/data-init-eval-menu.sql}（刀5，手跑）；码与 {@code @SaCheckPermission} 对齐。
 */
public final class EvalPermissions {

    /** 查看套件与用例 */
    public static final String SUITE_VIEW = "eval:suite:view";
    /** 新建、修改、导入、删除套件与用例 */
    public static final String SUITE_EDIT = "eval:suite:edit";

    /** 发起跑批（建 PENDING run 与占位结果行） */
    public static final String RUN_TRIGGER = "eval:run:trigger";
    /** 查看跑批状态与逐 case 结果 */
    public static final String RUN_VIEW = "eval:run:view";
    /** 取消跑批 */
    public static final String RUN_CANCEL = "eval:run:cancel";

    private EvalPermissions() {
    }
}
