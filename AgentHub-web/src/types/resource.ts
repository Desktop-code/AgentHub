/**
 * 通用「资源选择器」数据结构。所属域：前端通用（components/common）。
 *
 * 供 ResourceSelector 双栏穿梭框复用：工具 / Skill / 后续模型等「多选 + 分组 + 富信息」
 * 的绑定场景都压成同一份结构，业务侧只负责把接口数据映射成 ResourceGroup[]，
 * 展示细节交给 `item` / `selected-item` 插槽，避免每个业务各写一套穿梭逻辑。
 */

/**
 * 单个候选项。
 *
 * 约定：`id` 必须是业务主键（数字或字符串），左右两栏与 v-model 都按它对齐；
 * 展示字段只服务于默认插槽，业务自定义插槽时可只填 id。
 */
export interface ResourceOption {
  /** 业务主键；v-model 里存的就是它 */
  id: number | string
  /** 主展示名（中文名 / 标题），默认插槽加粗显示 */
  name: string
  /** 次展示名（英文代码名 / 编码），默认插槽用灰色小字显示 */
  code?: string
  /** 一句话说明，默认插槽在名称下方单行省略 */
  description?: string
  /**
   * 置灰标记：true 表示该资源当前不可用（如工具已停用）。
   * 语义是「不可新勾选」，若它已被选中仍允许取消，避免出现选了却取消不掉的死角。
   */
  disabled?: boolean
  /** 附注文案（来源名 / 版本号等），由业务插槽决定怎么用 */
  extra?: string
  /** 标签集合（如 Skill 触发关键词），由业务插槽决定怎么用 */
  tags?: string[]
  /**
   * 数据源里已找不到该资源（目标被删除）。
   * 由组件在右侧已选区兜底合成，业务侧无需传。
   */
  missing?: boolean
}

/**
 * 分组。
 *
 * 左侧按数组顺序渲染分组；搜索命中为空的分组整体隐藏，分组顺序不变。
 */
export interface ResourceGroup {
  /** 分组标题，如「内置工具」「MCP 工具源」「Skill 库」 */
  groupName: string
  /** 组内候选项 */
  items: ResourceOption[]
}
