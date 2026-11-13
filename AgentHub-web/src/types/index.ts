/**
 * 与后端 Result、分页和 /me 对齐的类型。所属域：前端契约。
 */
export interface Result<T> {
  code: number
  msg: string
  data: T
}

export interface PageQuery {
  current?: number
  size?: number
}

export interface PageResult<T> {
  total: number
  current: number
  size: number
  records: T[]
}

export interface MenuNode {
  id: number
  parentId: number
  /** 1 可折叠目录 / 2 菜单 / 4 目录（无折叠） */
  type?: number
  name: string
  path: string
  component?: string
  icon?: string
  sort?: number
  visible?: number
  children?: MenuNode[]
}

export interface MeInfo {
  userId: number
  username: string
  nickname: string
  /** 头像相对 URL；未上传为空，侧栏用首字兜底 */
  avatarUrl?: string | null
  deptId?: number
  deptName?: string
  platformAdmin: boolean
  roles: string[]
  permissions: string[]
  menus: MenuNode[]
}

export interface UserRow {
  id: number
  username: string
  nickname?: string
  deptId?: number
  deptName?: string
  email?: string
  phone?: string
  status: number
  platformAdmin: boolean
  roleIds: number[]
  roleNames: string[]
  createTime?: string
}

export interface RoleRow {
  id: number
  code: string
  name: string
  dataScope: number
  dataScopeLabel?: string
  sort: number
  status: number
  remark?: string
  menuIds?: number[]
  customDeptIds?: number[]
}

export interface MenuRow {
  id: number
  parentId: number
  type: number
  name: string
  path?: string
  component?: string
  redirect?: string
  permission?: string
  icon?: string
  sort: number
  visible: number
  status: number
  openType: number
  children?: MenuRow[]
}

export interface DeptRow {
  id: number
  parentId: number
  name: string
  path?: string
  sort: number
  leaderUserId?: number
  phone?: string
  status: number
  children?: DeptRow[]
}

export interface AppRow {
  id: number
  appKey: string
  name: string
  deptId?: number
  deptName?: string
  status: number
  remark?: string
  createTime?: string
  agentIds: number[]
  appSecret?: string
}

export interface LoginLogRow {
  id: number
  username?: string
  ip?: string
  success: number
  message?: string
  createTime?: string
}

export interface OperLogRow {
  id: number
  username?: string
  module?: string
  action?: string
  targetType?: string
  targetId?: string
  requestMethod?: string
  requestUrl?: string
  ip?: string
  detailJson?: string
  createTime?: string
}

/**
 * 结构化确认弹窗的键值行。所属域：前端通用组件契约。
 * tone 让「维度不一致」「不可撤销」这类关键值直接在弹窗里变色，不靠正文里的感叹号。
 */
export interface ConfirmRow {
  label: string
  /** 值允许较长（集合名、实例名），弹窗内自动换行 */
  value: string
  tone?: 'default' | 'warning' | 'danger'
}

/**
 * 结构化确认弹窗的提示块。type 决定底色与图标：
 * info=补充说明，warning=有代价但可继续，danger=不可撤销 / 会造成不可用。
 */
export interface ConfirmNotice {
  type: 'info' | 'warning' | 'danger'
  text: string
}
