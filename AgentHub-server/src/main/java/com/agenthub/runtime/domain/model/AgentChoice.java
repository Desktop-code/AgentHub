package com.agenthub.runtime.domain.model;

import java.util.List;
import java.util.Optional;

/**
 * 一轮回答末尾的「需要用户拍板」结构。所属域：runtime。
 *
 * <p>来源有两条，优先级固定：
 * <ol>
 *   <li><b>结构化产出</b>：模型调用平台内部工具 {@code agenthub_choice}，参数就是本结构；</li>
 *   <li><b>后端整理</b>：模型没调工具时，由后端拿终答再过一次小模型，收成同一份结构。</li>
 * </ol>
 * 收成后落成 {@link EventType#CHOICE} 事件，前端只渲染它，不再从正文猜列表。
 *
 * <p>字段名即前后端契约（question / options[{id,label}]），改名属协议变更。
 * 历史会话里可能还有旧的 {@code agenthub-choice} 机器块，仍由
 * {@code WorkbenchChoiceJson.parse} 兼容读取，但新写入不再使用机器块。
 *
 * @param question 一句话问题（可空：模型只给选项时由前端省略问题行）
 * @param options  选项列表；顺序即展示顺序
 */
public record AgentChoice(String question, List<Option> options) {

    /** 选项数量下限：一个选项不成其为「拍板」 */
    public static final int MIN_OPTIONS = 2;
    /** 选项数量上限：再多就不该让用户点，而应让模型自己收敛 */
    public static final int MAX_OPTIONS = 6;

    /** 防御性归一：null → 空表，避免调用方逐处判空。 */
    public AgentChoice {
        options = options == null ? List.of() : List.copyOf(options);
    }

    /**
     * 单个可点选项。
     *
     * @param id    稳定短标识（同一块内唯一；用于续作时定位用户点了哪一个）
     * @param label 展示文案（也是续作时写进 USER 事件的用户输入）
     */
    public record Option(String id, String label) {
    }

    /**
     * 统一校验并归一一份选项。工具调用、后端整理、旧机器块三条来源都必须过这里，
     * 保证落库 / 展示 / 续作看到的边界完全一致。
     *
     * <p>不合格一律整份作废（返回 null），不做「挑几条凑数」：宁可不出 chips，
     * 也不给一组点了会对错人的按钮。
     *
     * @param question 一句话问题，可空
     * @param options  候选选项，可空
     * @return 合法结构；任一条不合格时返回 null
     */
    public static AgentChoice of(String question, List<Option> options) {
        if (options == null) {
            return null;
        }
        List<Option> normalized = new java.util.ArrayList<>();
        for (Option option : options) {
            if (option == null) {
                return null;
            }
            String id = option.id() == null ? "" : option.id().trim();
            String label = option.label() == null ? "" : option.label().trim();
            // id / label 是定位与展示的双要素：缺一不可；id 重复会让续作点位歧义
            if (id.isEmpty() || label.isEmpty()) {
                return null;
            }
            if (normalized.stream().anyMatch(item -> item.id().equalsIgnoreCase(id))) {
                return null;
            }
            normalized.add(new Option(id, label));
        }
        if (normalized.size() < MIN_OPTIONS || normalized.size() > MAX_OPTIONS) {
            return null;
        }
        return new AgentChoice(question == null ? "" : question.trim(), normalized);
    }

    /**
     * 按 id 找选项（大小写不敏感，兼容模型大小写漂移）。
     *
     * @param optionId 前端回传的标识
     * @return 命中的选项；没有时为空
     */
    public Optional<Option> find(String optionId) {
        if (optionId == null || optionId.isBlank()) {
            return Optional.empty();
        }
        String wanted = optionId.trim();
        for (Option option : options) {
            if (option != null && option.id() != null && option.id().trim().equalsIgnoreCase(wanted)) {
                return Optional.of(option);
            }
        }
        return Optional.empty();
    }

    /**
     * @return true 表示至少有一个选项（落库 / 展示前的最低门槛）
     */
    public boolean usable() {
        return options.size() >= MIN_OPTIONS && options.size() <= MAX_OPTIONS;
    }
}
