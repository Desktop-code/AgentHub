/**
 * 时间文本格式化。所属域：前端基础设施（展示层）。
 *
 * <p>后端多数接口返回的是 `LocalDateTime` 序列化结果，可能是
 * `2026-11-08T04:58:12`、`2026-11-08T04:58:12.123` 或带空格的同构文本。
 * 列表里直接展示会带 `T` 与毫秒尾巴，列宽被撑开还会换行，因此统一收敛到这里。
 *
 * <p>只做字符串裁剪，不解析成 Date：避免时区换算把后端已按平台时区给出的值再挪一次。
 */

/**
 * 秒级日期时间：`YYYY-MM-DD HH:mm:ss`，去掉 `T` 与毫秒。
 *
 * @param value 原始时间文本；空值返回 `—`
 * @return 可直接展示的文本
 */
export function formatDateTime(value?: string | null): string {
  if (!value) {
    return '—'
  }
  // 统一分隔符后截到秒：'2026-11-08 04:58:12.123' → '2026-11-08 04:58:12'
  return value.trim().replace('T', ' ').slice(0, 19)
}

/**
 * 分钟级日期时间：`YYYY-MM-DD HH:mm`，用于列宽紧张的行内展示。
 *
 * @param value 原始时间文本；空值返回 `—`
 * @return 可直接展示的文本
 */
export function formatMinute(value?: string | null): string {
  if (!value) {
    return '—'
  }
  return value.trim().replace('T', ' ').slice(0, 16)
}
