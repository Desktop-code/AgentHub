/**
 * Agent 列表筛选。所属域：agent。
 * 仅网格态工具栏使用；主从左列用本地关键词过滤，不写这里。
 * 网格↔主从同页 viewMode 切换，筛选条件留在模块里以免丢。
 */
import { reactive } from 'vue'

export const agentBrowse = reactive({
  keyword: '',
  runMode: 'ALL',
  status: 'ALL',
  current: 1,
  /**
   * 每页条数。首屏由一屏容量实测写入（composables/gridPageSize.ts，列 × 行，上限 100）；
   * 这里的 10 只是测量完成前的占位值，不是固定分页大小。
   */
  size: 10
})
