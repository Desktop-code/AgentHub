package com.agenthub.runtime.application.router;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.MaDef;

import java.util.ArrayList;
import java.util.List;

/**
 * 编排用的人话标签与小工具。所属域：runtime。
 *
 * <p>只做字符串归一，不查库、不读配置；模板执行器与汇总器共用，避免两处各写一份
 * 「名称 → 编码 → 兜底文案」的兜底链。
 */
public final class RouterTexts {

    /** 判路由提示词里单个专家简介的硬顶：简介本身可到 1000 字，全量拼进提示词会挤掉候选列表。 */
    private static final int DESCRIPTION_MAX = 300;

    private RouterTexts() {
    }

    /**
     * 子 Agent 显示名：名称 → 编码 → 兜底文案。
     *
     * @param agent 子 Agent；可空
     * @return 非空展示名
     */
    public static String childLabel(AgentDef agent) {
        if (agent == null) {
            return "子 Agent";
        }
        if (agent.getName() != null && !agent.getName().isBlank()) {
            return agent.getName().trim();
        }
        if (agent.getCode() != null && !agent.getCode().isBlank()) {
            return agent.getCode().trim();
        }
        return "子 Agent";
    }

    /**
     * 路由展示摘要。无 route_key 时不拼空括号；子 Agent 优先名称，否则编码。
     *
     * <p>理由只来自判路由模型；为空时不要拼「依据：」，让前端降级成中性句，
     * 禁止在这里补「父模型」等系统标签冒充理由。
     *
     * @param hit    命中的候选
     * @param reason 判定依据；可空
     * @return 「路由到 …」中文摘要
     */
    public static String routeSummary(Candidate hit, String reason) {
        String label = childLabel(hit.agent());
        String key = hit.route().getRouteKey() == null ? "" : hit.route().getRouteKey().trim();
        String head = key.isEmpty() ? "路由到 " + label : "路由到 " + label + "（" + key + "）";
        String text = reason == null ? "" : reason.trim();
        return text.isEmpty() ? head : head + "，依据：" + text;
    }

    /**
     * 决策块里的去重展示名（按出现顺序）。
     *
     * <p>只用于拼人话与打点计数，不参与分流：条数按真实判定次数，名单按展示名去重，
     * 避免同名专家出现两次。
     *
     * @param intents 已排好序的意图；可空
     * @return 去重后的展示名；空表返回空列表
     */
    public static List<String> distinctLabels(List<RoutedIntent> intents) {
        List<String> names = new ArrayList<>();
        if (intents == null || intents.isEmpty()) {
            return names;
        }
        for (RoutedIntent intent : intents) {
            if (intent == null || intent.candidate() == null) {
                continue;
            }
            String label = childLabel(intent.candidate().agent());
            if (!label.isBlank() && !names.contains(label)) {
                names.add(label);
            }
        }
        return names;
    }

    /**
     * 多意图决策块的<b>人话</b>版：只写「判定出几个意图、按序分给谁」。
     *
     * <p>机器可读的 {@code [{"route_key","sub_query","reason"}]} 只用于分流与可观测，
     * 一律不进 ROUTE 事件正文：那是用户和模型都能看到的文本，把内部键打出去既泄露实现，
     * 也会被方案块当成「方案自己的回答」。JSON 仍写在 ROUTE span 的
     * {@code route_decision} attrs 里，排障不受影响。
     *
     * @param intents 已按判定序排好的意图；可空
     * @return 形如「路由决策\n已判定 2 个意图，按序分给「甲」「乙」」；空表返回空串
     */
    public static String decisionLine(List<RoutedIntent> intents) {
        if (intents == null || intents.isEmpty()) {
            return "";
        }
        List<String> names = distinctLabels(intents);
        StringBuilder text = new StringBuilder("路由决策\n已判定 ").append(intents.size()).append(" 个意图");
        if (!names.isEmpty()) {
            text.append("，按序分给");
            for (String name : names) {
                text.append("「").append(name).append("」");
            }
        }
        return text.toString();
    }

    /**
     * 一条消息拆成多个问题时的决策块：按问题顺序列出各问交给谁。
     *
     * <p>与 {@link #decisionLine} 的区别只在措辞：这里的事实是「几个问题」，
     * 不是「一个问题的几个意图」。目标名仍按出现顺序去重展示，不暴露 route_key。
     *
     * @param intents 已按问题顺序排好的意图；可空
     * @return 形如「路由决策\n已拆成 2 个问题，按序交给「企业综合台」「制度要点与报告」」；空表返回空串
     */
    public static String questionDecisionLine(List<RoutedIntent> intents) {
        if (intents == null || intents.isEmpty()) {
            return "";
        }
        List<String> names = distinctLabels(intents);
        StringBuilder text = new StringBuilder("路由决策\n已拆成 ").append(intents.size())
                .append(" 个问题，按序交给");
        for (String name : names) {
            text.append("「").append(name).append("」");
        }
        return text.toString();
    }

    /**
     * 子分段标题：优先展示路由意图描述，缺失时退回子 Agent 名。
     *
     * <p>方案（ma_def）候选不再把 {@code intent_desc} 搬进 route.intent（意图说明单一真源
     * 在 Agent 简介），因此这里对方案链路天然落到 Agent 名；旧 {@code agent_route} 链路
     * 仍可带自己的意图文案。
     *
     * @param outcome 子结果
     * @return 非空标题
     */
    public static String sectionTitle(ChildOutcome outcome) {
        Candidate hit = outcome.intent().candidate();
        String intent = hit.route().getIntent();
        if (intent != null && !intent.isBlank()) {
            return intent.trim();
        }
        return childLabel(hit.agent());
    }

    /**
     * 落库拼接稿的章节名：与参与者卡、begin 身份用同一套展示名。
     *
     * <p>路由意图文案可以和专家名不一样。拼进用户可见的 {@code ###} 标题后，
     * 回放按卡名对不上，正文就归不回这位专家。投票和汇总提示词仍用 {@link #sectionTitle}。
     *
     * @param outcome 子结果；缺候选时退回「子 Agent」
     * @return 非空展示名
     */
    public static String concatHeading(ChildOutcome outcome) {
        if (outcome == null || outcome.intent() == null || outcome.intent().candidate() == null) {
            return "子 Agent";
        }
        return childLabel(outcome.intent().candidate().agent());
    }

    /**
     * 判路由用的职责简介：只读 Agent 自己的简介（{@code agent_def.description}）。
     *
     * <p>简介是「意图说明」的唯一真源；为空就返回 null，让提示词只列专家名，
     * 不用空串或内部 id 冒充职责说明。
     *
     * @param agent 候选 Agent；可空
     * @return trim 后的简介（硬顶 {@link #DESCRIPTION_MAX} 字符）；无简介返回 null
     */
    public static String descriptionOf(AgentDef agent) {
        return agent == null ? null : clipDescription(agent.getDescription());
    }

    /**
     * 判路由用的方案职责简介：只读方案自己的说明（{@code ma_def.description}）。
     *
     * <p>刀5 起「另一套编排方案」也可作为路由候选，它的意图说明同为单一真源：
     * 方案说明，不读 {@code ma_candidate.intent_desc}。
     *
     * @param def 目标方案；可空
     * @return trim 后的说明（硬顶 {@link #DESCRIPTION_MAX} 字符）；无说明返回 null
     */
    public static String descriptionOf(MaDef def) {
        return def == null ? null : clipDescription(def.getDescription());
    }

    /**
     * 纯文本说明的统一裁剪（已有 {@code MaRunSpec.description()} 这类已取出的字符串时用）。
     *
     * @param raw 原始说明；可空
     * @return trim 后的说明（硬顶 {@link #DESCRIPTION_MAX} 字符）；空返回 null
     */
    public static String descriptionOfText(String raw) {
        return clipDescription(raw);
    }

    /** 简介 / 说明的统一裁剪：空 → null，超长按提示词预算截断。 */
    private static String clipDescription(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        return text.length() <= DESCRIPTION_MAX ? text : text.substring(0, DESCRIPTION_MAX);
    }
}
