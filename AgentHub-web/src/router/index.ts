/**
 * 路由与登录守卫。所属域：前端导航。
 * 页面是静态注册的；侧栏数据来自 /me，路径必须和这里一致。
 * 旧地址 /agent/trial 不再进独立页，改写到 /agent/list?agentId=&trial=1，由壳层打开抽屉。
 */
import { createRouter, createWebHistory } from 'vue-router'
import type { LocationQueryValue } from 'vue-router'
import { useUserStore } from '@/stores/user'

/** 把 query 收成单个字符串。数组取第一项。 */
function queryText(raw: LocationQueryValue | LocationQueryValue[] | undefined) {
  const value = Array.isArray(raw) ? raw[0] : raw
  return typeof value === 'string' ? value : ''
}

/** 列表和详情共用这一份，筛选条件在点进卡片后还在。 */
const agentManage = () => import('@/views/agent/list/index.vue')

/*
 * meta 约定（多页签 + KeepAlive 依赖，勿随意删改）：
 * - title：页签标题；有它就不再回查菜单树。
 * - cacheName：必须与页面组件 defineOptions({ name }) 完全一致，
 *   因为 KeepAlive 的 include 比对的是组件名而不是路由名。
 *   Agent 列表与详情共用同一个 cacheName：它们是同一页的两种内部状态，
 *   共用后只出一个页签、且切 URL 不重挂组件（翻卡动画依赖这一点）。
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('@/views/login/index.vue'), meta: { public: true } },
    {
      path: '/',
      component: () => import('@/views/layout/AdminLayout.vue'),
      children: [
        { path: '', redirect: '/workspace' },
        {
          path: 'workspace',
          component: () => import('@/views/workspace/index.vue'),
          meta: { title: '工作台', cacheName: 'Workspace' }
        },
        {
          path: 'system/user',
          component: () => import('@/views/system/user/index.vue'),
          meta: { title: '用户管理', cacheName: 'SystemUser' }
        },
        {
          path: 'system/role',
          component: () => import('@/views/system/role/index.vue'),
          meta: { title: '角色管理', cacheName: 'SystemRole' }
        },
        {
          path: 'system/menu',
          component: () => import('@/views/system/menu/index.vue'),
          meta: { title: '菜单管理', cacheName: 'SystemMenu' }
        },
        {
          path: 'system/dept',
          component: () => import('@/views/system/dept/index.vue'),
          meta: { title: '部门管理', cacheName: 'SystemDept' }
        },
        {
          path: 'system/app',
          component: () => import('@/views/system/app/index.vue'),
          meta: { title: '应用管理', cacheName: 'SystemApp' }
        },
        {
          path: 'system/login-log',
          component: () => import('@/views/system/login-log/index.vue'),
          meta: { title: '登录日志', cacheName: 'SystemLoginLog' }
        },
        {
          path: 'system/oper-log',
          component: () => import('@/views/system/oper-log/index.vue'),
          meta: { title: '操作日志', cacheName: 'SystemOperLog' }
        },
        {
          path: 'access/models',
          component: () => import('@/views/model/index.vue'),
          meta: { title: '模型管理', cacheName: 'ModelManage' }
        },
        {
          path: 'access/tools',
          component: () => import('@/views/tool/index.vue'),
          meta: { title: '工具管理', cacheName: 'ToolManage' }
        },
        {
          path: 'access/skills',
          component: () => import('@/views/skill/index.vue'),
          meta: { title: 'Skill 管理', cacheName: 'SkillManage' }
        },
        {
          path: 'access/prompt-center',
          component: () => import('@/views/prompt/center/index.vue'),
          meta: { title: '提示词模板库', cacheName: 'PromptCenter' }
        },
        /*
         * 旧入口兼容：L1/L2 曾各占一个页面与菜单，现已合成一页。
         * 保留旧 path 做重定向，历史收藏/外部链接进来仍能落到合成页；
         * 侧栏不再渲染这两项（菜单已合并为「提示词模板库」一项）。
         */
        { path: 'access/prompt-constraint', redirect: '/access/prompt-center' },
        { path: 'access/prompt-templates', redirect: '/access/prompt-center' },
        {
          path: 'access/kb',
          component: () => import('@/views/rag/kb/index.vue'),
          meta: { title: '知识库', cacheName: 'RagKbManage' }
        },
        /*
         * 知识库下的文档管理二级页。路径独立挂在 /rag/kb/:id/docs（不进侧栏），
         * 侧栏高亮仍归「知识库」菜单，见 AdminLayout.isCurrent 的前缀特判。
         * cacheName 固定：同一组件按 :id 参数切换知识库，靠页面内 watch 重新拉数据，
         * 不重挂组件（与 Agent 列表/详情同一套路），页签也只保留一个。
         */
        {
          path: 'rag/kb/:id/docs',
          component: () => import('@/views/rag/docs/index.vue'),
          meta: { title: '文档管理', cacheName: 'RagKbDocs' }
        },
        {
          path: 'ops/billing',
          component: () => import('@/views/billing/index.vue'),
          meta: { title: 'Token 用量', cacheName: 'BillingUsage' }
        },
        {
          path: 'ops/traces',
          component: () => import('@/views/obs/trace/index.vue'),
          meta: { title: '调用追踪', cacheName: 'CallTrace' }
        },
        {
          path: 'ops/eval',
          component: () => import('@/views/eval/console/index.vue'),
          meta: { title: 'Agent 评测', cacheName: 'EvalConsole' }
        },
        /*
         * 编排 → 工作流（决议 M6：独立一级目录，不塞进 Agent run_mode）。
         * 列表与编辑分开 cacheName：编辑页是重画布，不跟列表共用缓存；
         * 编辑页按 :id 复用同一组件，页面内 watch 参数重新载入。
         */
        {
          path: 'orchestration/workflow',
          component: () => import('@/views/workflow/list/index.vue'),
          meta: { title: '工作流', cacheName: 'WorkflowList' }
        },
        {
          path: 'orchestration/workflow/:id',
          component: () => import('@/views/workflow/editor/index.vue'),
          meta: { title: '工作流编辑', cacheName: 'WorkflowEditor' }
        },
        /*
         * 编排 → 人工待办（WF-P0 刀3）：独立薄页，只做「看待办 + 通过 / 驳回」，
         * 不是外部门户，也不复用画布编辑页。
         */
        {
          path: 'orchestration/human-tasks',
          component: () => import('@/views/workflow/human-tasks/index.vue'),
          meta: { title: '人工待办', cacheName: 'WorkflowHumanTasks' }
        },
        /*
         * 编排 → 多智能体（MA-P1 刀2）：与「工作流」同级的独立列表页。
         * 只读入口：数据是 run_mode=ROUTER 的 Agent；编辑仍回 /agent/list/:id 的「编排」Tab，
         * 因此本页不注册详情子路由，也不复用 WorkflowEditor。
         */
        {
          path: 'orchestration/multi-agent',
          component: () => import('@/views/ma/list/index.vue'),
          meta: { title: '多智能体', cacheName: 'MaList' }
        },
        /*
         * 编排 → 多智能体 → 独立试跑页（MA-UI 刀2）。
         * 全屏对话壳，复用试跑面板与对话流内的编排调度卡；不是第二套执行引擎，
         * 服务端仍走 RouterTrialExecutor。同一 cacheName 只占一个页签，
         * 换 agentId 由页面内 watch 重新载入（TrialPanel 按 agentId 重挂）。
         */
        {
          path: 'orchestration/multi-agent/:agentId/trial',
          component: () => import('@/views/ma/trial/index.vue'),
          meta: { title: '多智能体试跑', cacheName: 'MaTrial' }
        },
        {
          path: 'agent/memory',
          component: () => import('@/views/memory/index.vue'),
          meta: { title: '记忆管理', cacheName: 'MemoryManage' }
        },
        // 列表 → 详情只换 URL，页面内部自行 watch 参数；cacheName 相同即同一个页签
        {
          path: 'agent/list',
          component: agentManage,
          meta: { title: 'Agent 管理', cacheName: 'AgentManage' }
        },
        {
          path: 'agent/list/:id',
          component: agentManage,
          meta: { title: 'Agent 管理', cacheName: 'AgentManage' }
        }
      ]
    }
  ]
})

router.beforeEach(async (to) => {
  const user = useUserStore()
  if (to.meta.public) {
    if (user.token && to.path === '/login') {
      return '/'
    }
    return true
  }
  if (!user.token) {
    return '/login'
  }
  // 刷新后 Pinia 是空的，先补一次 /me，侧栏和按钮才有权限
  if (!user.loaded) {
    try {
      await user.loadMe()
    } catch {
      await user.logout()
      return '/login'
    }
  }
  // 独立试跑页不再作为入口。有 Agent 时落到列表 query，由壳层打开抽屉。
  if (to.path === '/agent/trial') {
    const fromQuery = Number(queryText(to.query.agentId))
    const queryId = Number.isFinite(fromQuery) && fromQuery > 0 ? fromQuery : undefined
    if (queryId) {
      return { path: '/agent/list', query: { agentId: String(queryId), trial: '1' } }
    }
    return { path: '/agent/list' }
  }
  return true
})

export default router
