/**
 * 对话区跟滚。所属域：runtime（前端展示）。
 *
 * <p>职责：只管「滚动位置 + 帧合批 + 自己写入的 scrollTop」三件事，和消息内容 / 投影无关。
 * 挂在原来的滚动容器上，不包 `<MessageList>` / `<ScrollPane>` 之类的列表壳。
 *
 * <p>行为边界（与拆前一致）：
 * - 发问、切换会话（首轮 key 变化）视为「要看最新内容」，强制回到底部；
 * - 用户主动上翻后不再拽回，直到他自己滚回底部附近；
 * - 程序写 scrollTop 引发的 scroll 事件不当作上翻，判定交给
 *   {@link resolveFollowAfterScroll}（保持原样，不改纯函数口径）。
 */
import { onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue'
import type { ChatTurn } from '@/types/runtime'
import { resolveFollowAfterScroll } from '@/components/runtime/chatFollow'

/** 距底部多少像素内算「仍在底部」；略大于一行高度，避免轻微抖动就被判成上翻 */
const NEAR_BOTTOM_PX = 80

/**
 * 把跟滚状态与生命周期收进组合式，挂在现有滚动容器上。
 *
 * @param scroller  对话滚动容器（组件里的 `ref="scroller"`）
 * @param turns     读取当前轮次列表；只用首轮 key 判断「切会话」
 * @param isSending 读取是否正在发送；false→true 视为用户刚发问
 * @return 模板与 `onUpdated` 需要的滚动句柄：`nearBottom` / `onScroll` / `followToBottom` / `scheduleFollowBottom`
 */
export function useChatFollow(
  scroller: Ref<HTMLElement | undefined>,
  turns: () => ChatTurn[],
  isSending: () => boolean
) {
  /** 用户是否仍停在对话底部附近；只有 true 时才跟滚 */
  const nearBottom = ref(true)

  /** 已排队但还没执行的跟滚帧；同一帧内的多次更新只滚一次 */
  let followFrame = 0

  /**
   * 上一次由程序写入 `scrollTop` 后读回的实际值（-1 表示本轮还没写过）。
   *
   * <p>用途：区分「用户滚动」与「我们自己滚到底」。后者同样会派发 scroll 事件，
   * 且派发时正文可能又长了，若当成用户上翻就会把跟滚关掉。
   */
  let selfScrollTop = -1

  /**
   * 记录滚动位置：只在滚动事件里读一次布局，判断是否仍在底部附近。
   * 把布局读取从「每个增量」挪到「用户真的滚动」，避免流式期每帧强制同步布局。
   */
  function onScroll() {
    const element = scroller.value
    if (!element) {
      return
    }
    nearBottom.value = resolveFollowAfterScroll({
      scrollTop: element.scrollTop,
      scrollHeight: element.scrollHeight,
      clientHeight: element.clientHeight,
      selfScrollTop,
      previous: nearBottom.value,
      threshold: NEAR_BOTTOM_PX
    })
  }

  /**
   * 强制恢复「跟到底部」。
   *
   * <p>用于语义上必然是「要看最新内容」的时刻：用户刚发问、刚切到另一个会话。
   * 这两处若沿用上一次的滚动状态，从旧会话带着「已上翻」进来就会一直不滚。
   */
  function followToBottom() {
    nearBottom.value = true
    scheduleFollowBottom()
  }

  /**
   * 把跟滚合批到下一帧：一帧最多读一次 scrollHeight、写一次 scrollTop。
   *
   * <p>由外层 `onUpdated` 在内容（含 Markdown 增量）真正写进 DOM 之后触发；
   * 用户已上翻则直接跳过，不抢回滚动位置。
   */
  function scheduleFollowBottom() {
    if (followFrame) {
      return
    }
    followFrame = requestAnimationFrame(() => {
      followFrame = 0
      if (!nearBottom.value) {
        return
      }
      const element = scroller.value
      if (!element) {
        return
      }
      // 读布局与写滚动在同一帧完成，只触发一次布局失效，替代原先每个 delta 一次
      element.scrollTop = element.scrollHeight
      // 读回钳制后的真实值：内容不足一屏时会被夹到 0，下次判定要认这个值
      selfScrollTop = element.scrollTop
    })
  }

  // 用户发问（sending 由 false 变 true）即视为「要看最新内容」：无论之前是否上翻，都重新跟到底
  watch(isSending, (now) => {
    if (now) {
      followToBottom()
    }
  })

  /**
   * 会话身份（首轮 key）变化：新建会话或切到另一个会话，都要从底部开始看。
   * 不能只依赖上一次的滚动状态，否则从「上翻着的旧会话」切过来会停在顶部。
   */
  watch(
    () => turns()[0]?.key ?? '',
    () => {
      followToBottom()
    }
  )

  // 挂载时先定位到最新一条：抽屉重开 / 页签切回来时没有数据变化，不会触发 onUpdated
  onMounted(followToBottom)

  // 卸载时取消未执行的帧，避免回调在已销毁组件上读 DOM
  onBeforeUnmount(() => {
    if (followFrame) {
      cancelAnimationFrame(followFrame)
      followFrame = 0
    }
  })

  return { nearBottom, onScroll, followToBottom, scheduleFollowBottom }
}
