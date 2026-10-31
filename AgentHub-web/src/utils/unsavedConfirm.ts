/**
 * 「有未保存的更改」确认框。所属域：前端基础设施。
 * 供切 Tab / 切对象 / 关抽屉等会丢弃草稿的入口统一调用，避免各页文案与按钮不一致。
 * 单独成文件（不放进 useFormDirty）是为了让脏检查单测不必加载 Element Plus。
 */
import { ElMessageBox } from 'element-plus'

/**
 * 询问是否丢弃未保存的草稿。
 *
 * @param scene 场景名（如「Agent 配置」「提示词草稿」），用于拼提示文案；
 *              **不要**自带「有未保存的更改」，本函数会补
 * @return true 表示允许继续（丢弃草稿）；false 表示留在当前页面继续编辑
 */
export async function confirmDiscardUnsaved(scene = '当前配置'): Promise<boolean> {
  try {
    await ElMessageBox.confirm(`${scene}有未保存的更改，继续将丢弃这些修改。`, '未保存的更改', {
      confirmButtonText: '丢弃更改',
      cancelButtonText: '继续编辑',
      type: 'warning'
    })
    return true
  } catch {
    // 关闭弹窗 / 点「继续编辑」都走 reject：一律视为不继续
    return false
  }
}
