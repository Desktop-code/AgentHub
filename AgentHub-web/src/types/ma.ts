/**
 * 多智能体列表类型。所属域：前端 agent。
 *
 * <p>一行对应一个编排根（run_mode=ROUTER 的 Agent 定义），不是独立 ma_def 实体；
 * 编辑仍回 Agent 详情「编排」Tab。
 */
export interface MaListRow {
  /** Agent 主键；跳转用 */
  id: number
  /** Agent 名称 */
  name: string
  /** 编码，便于和 Agent 管理对照 */
  code: string
  /** 启停：1 正常，0 停用 */
  status: number
  /** 模板：ROUTER_PARALLEL | FIXED_SEQUENCE | FIXED_PARALLEL | CRITIC_LOOP | ROUTER_THEN_SEQUENCE；后端已归一，不会为空 */
  maTemplate: string
  /** 启用中的候选子 Agent 数（agent_route.status=1 且未删） */
  candidateCount: number
  /** 定义最后更新时间 */
  updateTime?: string
}
