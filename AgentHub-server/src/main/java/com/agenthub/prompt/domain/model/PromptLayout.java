package com.agenthub.prompt.domain.model;

import java.util.List;

/**
 * 组装结构描述（谁能被截断、谁不能）。所属域：prompt。
 * <p>
 * 为什么需要它：{@code PromptAssembler} 产出的是一个拼接好的字符串，下游再做预算裁剪时
 * 只看到一堆字符，无法区分「平台约束 / 锁定契约」和「可省的补充说明」。一旦按字符数硬切，
 * 首部就可能被砍掉，等于把红线截断后仍照常出站。
 * <p>
 * 本类把「受保护前缀长度 + 各段渲染后正文」显式交给下游：
 * <ul>
 *   <li>{@code headChars}：受保护前缀（L1 + 平台追加句 + 全部锁定段）在组装正文中的字符长度，
 *       下游截断必须以它为下界，禁止越过；</li>
 *   <li>{@code lockedBlocks}：锁定段渲染后正文，用于出站前校验「锁定契约确实在场」；</li>
 *   <li>{@code tailBlocks}：开放段 / 历史业务段 / L3 追加区渲染后正文，用于校验「锁定段在其之前」。</li>
 * </ul>
 *
 * <p>不可变：块列表在构造时复制，避免调用方改动后影响已出站记录。
 *
 * @param headChars     受保护前缀字符数，0 表示无结构信息（调用方不得据此截断首部）
 * @param lockedBlocks  锁定段渲染后正文，按出现顺序
 * @param tailBlocks    可截断段渲染后正文，按出现顺序
 */
public record PromptLayout(int headChars, List<String> lockedBlocks, List<String> tailBlocks) {

    /**
     * 空结构：用于未经过完整组装的链路（如仅手拼一段的平台提示）。
     * 下游按「无结构信息」处理：只保留原有的前缀校验，不做结构断言。
     */
    public static final PromptLayout EMPTY = new PromptLayout(0, List.of(), List.of());

    /**
     * 复制块列表，避免外部持有可变 List 后被就地修改。
     */
    public PromptLayout {
        lockedBlocks = lockedBlocks == null ? List.of() : List.copyOf(lockedBlocks);
        tailBlocks = tailBlocks == null ? List.of() : List.copyOf(tailBlocks);
    }

    /**
     * @return true 表示带有结构信息（headChars &gt; 0），下游可据此保护首部
     */
    public boolean structured() {
        return headChars > 0;
    }

    /**
     * @return true 表示该版模板至少有一个锁定段（出站前必须能找到它）
     */
    public boolean hasLockedBlock() {
        return !lockedBlocks.isEmpty();
    }
}
