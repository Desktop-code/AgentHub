package com.agenthub.runtime.domain.model;

/**
 * 一轮执行里「用户拍板选项」的采集槽。所属域：runtime。
 *
 * <p>为什么需要它：结构化产出的两条来源都不在请求线程上——
 * 工具 {@code agenthub_choice} 在引擎的工具线程执行，后端整理要等整轮结束才能读到终答。
 * 这里用一个可变对象把两侧接起来：
 * <ul>
 *   <li>请求线程创建槽并放进 {@link com.agenthub.runtime.domain.service.ChoiceCaptureHolder}；</li>
 *   <li>装配工具时把同一个槽交给工具实例（工具线程只写这个对象，不读 ThreadLocal）；</li>
 *   <li>整轮结束后请求线程读槽里的值，决定落 CHOICE 事件还是走整理。</li>
 * </ul>
 *
 * <p>线程安全：写入来自工具线程、读取在请求线程（工具执行完成后），字段用 volatile 保证可见性；
 * 多子并行时只有被允许出选项的那一位会安装工具，不存在多方竞争写。
 */
public final class ChoiceCapture {

    /** 本轮是否真的装配过「可点选项」结构（只有工作台单专家 / 续作会打开） */
    private volatile boolean eligible;

    /** 最近一次合法的结构化选项；模型改主意重调时以后一次为准 */
    private volatile AgentChoice choice;

    /**
     * 标记本轮允许用户拍板：由装配工具的一侧调用（说明提示词里已有选项说明段）。
     * 只有被标记过的轮次才会走「后端整理」，避免调试轮 / 多专家轮凭空多一次模型出站。
     */
    public void markEligible() {
        this.eligible = true;
    }

    /**
     * @return true 表示本轮装配了结构化选项能力
     */
    public boolean isEligible() {
        return eligible;
    }

    /**
     * 收下一份结构化选项。非法结构直接丢弃（工具那一侧已校验，这里再挡一次）。
     *
     * @param value 选项结构，可空
     */
    public void accept(AgentChoice value) {
        if (value == null || !value.usable()) {
            return;
        }
        this.choice = value;
    }

    /**
     * @return 模型通过工具给出的选项；没调工具或结构非法时为空
     */
    public AgentChoice choice() {
        return choice;
    }
}
