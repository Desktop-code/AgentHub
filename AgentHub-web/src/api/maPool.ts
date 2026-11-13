/**
 * 多智能体路由池的候选目标选项。所属域：前端 agent。
 *
 * <p>默认接待页整页下线后，这三组「可入池目标」仍是维护对话路由（入口）候选池所必需的：
 * 后端已按入池纪律过滤（剔除自己 / 入口 / 停用 / 不判路由 / 已挂方案候选 / 无说明），
 * 前端只负责渲染下拉，不在本地重写一遍过滤规则。
 *
 * <p>后端路径沿用 `/api/agent/multi-agents/reception/available-*`：它们只读、
 * 不承担任何接待配置写入，页面层不再存在「默认接待」入口。
 */
import request from '@/utils/request'
import type { Result } from '@/types'
import type { ReceptionSchemeOption, ReceptionWorkflowOption } from '@/types/reception'

/**
 * 可加入入口路由池的「另一套编排方案」。
 *
 * @return 方案选项；descriptionMissing=true 的目标不能提交（无说明无法判路由）
 */
export function listReceptionSchemeOptions() {
  return request.get<Result<ReceptionSchemeOption[]>>('/api/agent/multi-agents/reception/available-schemes')
}

/**
 * 可加入入口路由池的「已发布工作流」。
 *
 * <p>后端只返回同租户、未删除、有默认发布指针且说明非空、还没在池里的定义。
 *
 * @return 工作流选项
 */
export function listReceptionWorkflowOptions() {
  return request.get<Result<ReceptionWorkflowOption[]>>('/api/agent/multi-agents/reception/available-workflows')
}
