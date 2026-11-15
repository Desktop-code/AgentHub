/**
 * 平台内置 Agent 的稳定身份。所属域：agent（前端）。
 *
 * <p>识别口径与后端 {@code PlatformDefaultAssistantPolicy} 保持一致：只看业务编码，
 * 不看名称或主键。名称允许被改、主键各环境不同，只有种子脚本写入的编码跨环境稳定。
 *
 * <p>本阶段只服务「平台默认智能助手」一个对象：列表 / 详情隐藏停用与删除入口，
 * 卡片用很淡的透明蓝底区分。用户自建专家不受影响。
 */

/** 平台默认智能助手的稳定编码（与 data-init-workbench-reception.sql 种子一致） */
export const PLATFORM_DEFAULT_ASSISTANT_CODE = 'reception_assistant'

/**
 * 判断一条 Agent 是否为平台默认智能助手。
 *
 * @param code Agent 业务编码；列表 / 详情可能尚未加载，允许为空
 * @returns true 表示这条定义属于平台默认兜底接待
 */
export function isPlatformDefaultAssistant(code?: string | null): boolean {
  // 忽略大小写与首尾空白：库内大小写被手工改过时仍应命中锁定口径
  return typeof code === 'string' && code.trim().toLowerCase() === PLATFORM_DEFAULT_ASSISTANT_CODE
}
