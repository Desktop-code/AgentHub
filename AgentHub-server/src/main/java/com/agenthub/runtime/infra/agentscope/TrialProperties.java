package com.agenthub.runtime.infra.agentscope;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 试跑落库与历史参数。所属域：runtime。对应 agenthub.runtime.trial。
 * 轮次/超时已收拢到 agenthub.runtime.limits，见 {@link com.agenthub.common.config.RuntimeLimitsProperties}。
 */
@Data
@ConfigurationProperties(prefix = "agenthub.runtime.trial")
public class TrialProperties {

    /** content_text 单条字符上限。超出截断并注明已截断 */
    private int contentLimitChars = 32768;

    /**
     * 历史会话的<b>默认页大小</b>（只查当前用户和指定 Agent，按 start_time 倒序）。
     * <p>语义已改：不再是「任何请求都裁到这个条数」的硬顶。调用方传了 size 就按 size
     * （仍受全站 100 上限约束），没传才用它；真分页靠前端 current++ 翻页，不靠缩减总量。
     */
    private int historyLimit = 50;

    /**
     * 首轮结束后是否异步生成会话标题。默认开；
     * 关掉只是不生成标题（左栏退回展示首问预览），不影响试跑本身。
     */
    private boolean autoTitleEnabled = true;

    /**
     * 超长工具结果的落盘阈值（字符）。超过则写入会话工作区，
     * 对话里只留「摘要 + 虚拟路径」，模型需要细节再调 {@code workspaceReadFile}。
     * <p>
     * 为什么默认 2000：这个量级已经远超「一句结论」的信息量，
     * 继续整段回灌只会在后续每一轮重复计费；而 2000 字以内的结果直接给模型更省事。
     * 配 0 或负数表示关闭句柄化（退回单纯的字符截断）。
     */
    private int toolResultOffloadChars = 2000;

    /**
     * 落盘结果回灌时保留的头部摘要字符数。
     * <p>
     * 只取开头：标题、状态码、表头这些最有信息量的内容几乎都在前几百字。
     */
    private int toolResultOffloadPreviewChars = 600;

    /**
     * 落盘阈值：只有超过该长度的成功结果才会被写成文件。
     *
     * @return 字符阈值；≤0 表示关闭句柄化
     */
    public int offloadChars() {
        return toolResultOffloadChars;
    }

    /**
     * @return 回灌摘要保留的头部字符数，至少 0
     */
    public int offloadPreviewChars() {
        return Math.max(toolResultOffloadPreviewChars, 0);
    }
}
