package com.agenthub.runtime.domain.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 系统提示的唯一裁剪器。所属域：runtime。
 * <p>
 * 为什么要有它：历史上裁剪分两刀——{@code PromptAssembler} 按 {@code assembler.max-chars} 切一次，
 * {@code PromptInjectionComposer} 再按 {@code system-prompt-max-chars} 切一次。两刀各有各的口径，
 * 第一刀可能已经把「补充要求 / 业务提示词」砍掉，第二刀又不知道哪些段是可让出的，
 * 于是「砍了该留的、留了该砍的」。本类把这些收敛成<b>一次</b>按优先级让出的决策。
 * <p>
 * 硬约束：
 * <ul>
 *   <li>{@link Tier#PROTECTED} 永不让出：L1 平台约束 + 平台追加句 + 全部锁定段，
 *       预算再小也整段保留（宁可超预算也不把红线截成半句继续出站）；</li>
 *   <li>让出顺序固定（先让谁、后让谁只有 {@link #trim} 里那一处，不散落在调用方）：</li>
 * </ul>
 * <pre>
 * 1. 记忆追加区         整段让出（下一轮还能再召回）
 * 2. 按需 Skill         退成「标题 + 场景」（只剩段头）
 * 3. 工具目录文案       退成「名称 + 不可用标记」
 * 4. 可压区（tail）     从尾部按字符裁，L2 开放段 / L3 追加 / 历史业务段
 * 5. 强制 Skill         退成段头（系统强制规则，只有前四档都不够才动）
 * 6. 工具名             最后一个都不丢，只在尾部留痕
 * </pre>
 * <p>
 * 同档内<b>倒序</b>让出：先绑的 Skill / 先渲染的行优先保留。这与「按绑定序贪心」的旧口径一致，
 * 也避免出现「后面的技能把前面的挤掉」这种越绑越乱的观感。
 * <p>
 * 分段计量：本类只决定保留多少字符，统计口径由
 * {@link com.agenthub.runtime.domain.model.PromptInjectionStats} 汇总。
 * <p>
 * 线程安全：无状态，全部静态纯函数；{@link Section} 是调用方新建的可变工作对象，不跨线程共享。
 */
public final class PromptBudgetTrimmer {

    /** 让出档位。数值顺序不决定让出优先级，实际顺序只看 {@link #trim} 里的调用次序。 */
    public enum Tier {
        /** L1 + 平台追加句 + 锁定段：永不裁剪 */
        PROTECTED,
        /** 记忆追加区（【工作记忆】/【用户偏好】）：整段可省 */
        MEMORY,
        /** 按需 Skill：正文可省，标题 + 场景必须留 */
        OPTIONAL_SKILL,
        /** 工具目录：一句话说明可省，工具名必须留 */
        CATALOG,
        /** 可压区（L2 开放段 / L3 追加 / 历史业务段）：按字符从尾部裁 */
        SOFT_TAIL,
        /** 强制 Skill：段头必须留，正文可退 */
        FORCED_SKILL
    }

    /**
     * 让出顺序。改动 {@link #trim} 里的调用顺序等于改线上行为，必须同步
     * {@code docs/提示词工程.md} 的口径表。
     * <p>
     * 注意尾部裁字（{@link Tier#SOFT_TAIL}）排在「目录文案」之后、「强制 Skill」之前：
     * 开放段 / 业务段是语气与补充说明，先让它们缩水比丢掉可执行规则划算。
     */
    private PromptBudgetTrimmer() {
    }

    /**
     * 一个待拼段落。
     * <p>
     * {@code full} 是充裕时的完整写法，{@code slim} 是被让出后的最小写法（可以是空串）。
     * 用「两个现成写法」而不是「按比例截断」，是为了让退让结果可预测、可断言：
     * 没人愿意看到一句被砍掉一半的 Skill 场景说明。
     */
    public static final class Section {

        private final String id;
        private final Tier tier;
        private final String slim;
        /** 当前生效文本；让出时改为 slim，或由尾部裁字改写 */
        private String text;
        /** 是否发生过让出（整段替换或裁字），供调用方决定页脚文案 */
        private boolean yielded;

        /**
         * @param id   段标识（skill:标题 / memory / catalog / soft），只用于日志与统计
         * @param tier 让出档位
         * @param full 充裕时的完整文本；调用方自行带上与前文之间的换行
         * @param slim 让出后的最小文本；空串表示整段可省
         */
        public Section(String id, Tier tier, String full, String slim) {
            this.id = id;
            this.tier = tier;
            this.slim = slim == null ? "" : slim;
            this.text = full == null ? "" : full;
        }

        /**
         * 直接给定当前文本（不做让出替换的段，如 PROTECTED）。
         *
         * @param id   段标识
         * @param tier 档位
         * @param text 文本
         * @return 段
         */
        public static Section fixed(String id, Tier tier, String text) {
            return new Section(id, tier, text, text);
        }

        public String id() {
            return id;
        }

        public Tier tier() {
            return tier;
        }

        public String text() {
            return text;
        }

        public boolean yielded() {
            return yielded;
        }

        /**
         * @return 该段是否还有可退空间（slim 比当前文本短）
         */
        private boolean shrinkable() {
            return slim.length() < text.length();
        }

        /** 退到最小写法 */
        private int shrink() {
            int saved = text.length() - slim.length();
            text = slim;
            yielded = true;
            return saved;
        }
    }

    /**
     * 裁剪结果。
     *
     * @param text      拼接后的正文（段之间不额外加分隔，调用方已在文本里带好换行）
     * @param sections  各段裁剪后的最终文本，按出站顺序；供分段计量取实际长度
     * @param truncated 是否发生过任何让出或裁字
     * @param yielded   实际让出的段标识，按让出先后；为空表示预算够用
     * @param overBudget tail 与强制 Skill 都让完后仍超预算（只剩保护区，属于「宁可超预算」）
     */
    public record Outcome(String text, List<Section> sections, boolean truncated, List<String> yielded,
                          boolean overBudget) {

        /**
         * 复制段列表：段对象可变，调用方拿到后只应读取 {@code text()}。
         */
        public Outcome {
            sections = sections == null ? List.of() : List.copyOf(sections);
        }
    }

    /**
     * 按预算裁剪并拼接。
     *
     * @param sections 按最终出站顺序排好的段；保护区必须排在最前
     * @param budget   系统提示字符预算（保护区不参与扣减）
     * @return 裁剪结果
     */
    public static Outcome trim(List<Section> sections, int budget) {
        List<Section> work = sections == null ? List.of() : sections;
        int total = 0;
        for (Section section : work) {
            total += section.text().length();
        }
        int excess = total - Math.max(budget, 0);
        if (excess <= 0) {
            return new Outcome(join(work), work, false, List.of(), false);
        }
        List<String> yielded = new ArrayList<>();
        excess = shrinkTier(work, Tier.MEMORY, excess, yielded);
        excess = shrinkTier(work, Tier.OPTIONAL_SKILL, excess, yielded);
        excess = shrinkTier(work, Tier.CATALOG, excess, yielded);
        // 可压区（tail）按字符从尾部裁：整段让出太粗暴，用户配置的业务段往往只多出几十字
        excess = cutSoftTail(work, excess, yielded);
        excess = shrinkTier(work, Tier.FORCED_SKILL, excess, yielded);
        // 剩下的只可能是保护区：不越界去砍红线，只在尾部留痕（由调用方按 overBudget 决定文案）
        return new Outcome(join(work), work, true, List.copyOf(yielded), excess > 0);
    }

    /**
     * 某一档整体让出：同档内倒序处理，先渲染 / 先绑定者优先保留。
     *
     * @param sections 全部段
     * @param tier     档位
     * @param excess   仍需让出的字符数
     * @param yielded  让出记录
     * @return 剩余待让出字符数
     */
    private static int shrinkTier(List<Section> sections, Tier tier, int excess, List<String> yielded) {
        if (excess <= 0) {
            return excess;
        }
        for (int i = sections.size() - 1; i >= 0 && excess > 0; i--) {
            Section section = sections.get(i);
            if (section.tier() != tier || !section.shrinkable()) {
                continue;
            }
            excess -= section.shrink();
            yielded.add(section.id());
        }
        return excess;
    }

    /**
     * 可压区尾部裁字：从最后一个可压段往前吃，必要时整段吃掉。
     * <p>
     * 为什么放在「目录文案」之后、「强制 Skill」之前：强制 Skill 不经模型判断、必须执行，
     * 而开放段 / 业务段是语气与补充说明，先让它们缩水更划算。
     */
    private static int cutSoftTail(List<Section> sections, int excess, List<String> yielded) {
        if (excess <= 0) {
            return excess;
        }
        for (int i = sections.size() - 1; i >= 0 && excess > 0; i--) {
            Section section = sections.get(i);
            if (section.tier() != Tier.SOFT_TAIL || section.text().isEmpty()) {
                continue;
            }
            int cut = Math.min(excess, section.text().length());
            section.text = section.text().substring(0, section.text().length() - cut);
            section.yielded = true;
            yielded.add(section.id());
            excess -= cut;
        }
        return excess;
    }

    /**
     * @return 按入参顺序拼接全部段当前文本
     */
    private static String join(List<Section> sections) {
        StringBuilder text = new StringBuilder();
        for (Section section : sections) {
            text.append(section.text());
        }
        return text.toString();
    }
}
