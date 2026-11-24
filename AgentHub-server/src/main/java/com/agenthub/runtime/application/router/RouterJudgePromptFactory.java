package com.agenthub.runtime.application.router;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.runtime.application.SystemPromptAssembler;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.RouteHint;
import java.util.List;

/**
 * 判路由系统提示词装配。所属域：runtime（方案运行编排）。
 *
 * <p>从 {@code RouterTrialExecutor} 搬出，职责只有一件事：把平台前缀、网关调度指令、
 * 候选名单与调度规则拼成一份判路由系统提示。不调模型、不解析结果、不写事件。
 *
 * <p>口径保持不变：
 * <ul>
 *   <li>提示词逐字不变（含换行、标点、条数与编号顺序）；</li>
 *   <li>用户原文只占 {@link #JUDGE_USER_SLOT}，同一轮多问复用已装配前缀，仅替换槽；</li>
 *   <li>方案链路走 {@link SystemPromptAssembler#assembleRouter}，旧口径走 {@link SystemPromptAssembler#build}；
 *       用途模板口径不变。</li>
 * </ul>
 */
public class RouterJudgePromptFactory {

    /**
     * 用户原文槽：装配时先占位，避免 ROUTER 用途模板引用 {@code {{user_query}}} 时严格模式缺变量。
     * 替换进去的问句不再回扫，问句里即使带有槽字符也不会被当成占位符。
     */
    static final String JUDGE_USER_SLOT = "\u0001agenthub-user-query\u0001";

    /** 平台提示词装配（L1 / 种子契约 / 用途模板）。 */
    private final SystemPromptAssembler prompts;

    /**
     * @param prompts 平台提示词装配器
     */
    public RouterJudgePromptFactory(SystemPromptAssembler prompts) {
        this.prompts = prompts;
    }

    /**
     * 判路由提示词（内置组装）。
     *
     * <p>结构固定为三段，页面 / 方案都不再参与组装：
     * <ol>
     *   <li>网关调度指令：只派活、不答题；</li>
     *   <li>【可用专家】：本方案启用候选的 route_key｜展示名｜Agent 简介（简介单一真源在 Agent）；</li>
     *   <li>【调度规则】：只输出候选内的键；寒暄 / 问能力 / 无匹配 / 不当内容派给聊天机器人；
     *       禁止派给名单外或另一个 Router。</li>
     * </ol>
     *
     * <p>{@code hint} 有沿用线路时追加一段「本会话当前线路是 X」的说明：
     * 只建议优先沿用，不剥夺判路由改派的能力——用户明显换了业务仍应输出新键。
     * 提示词注入失败或模型不听话都以判路由结果为准，不阻塞主路径。
     *
     * @param parent     编排根（取用途模板前缀）
     * @param candidates 可用候选
     * @param runtime    运行配置（多意图开关 / 上限）
     * @param userText   用户原问（填进模板里的用户原文槽；调度规则与候选名单不随它变化）
     * @param hint       线路提示；可空
     * @param spec       方案执行来源；可空（旧口径没有方案）
     * @return 完整提示词
     */
    public String judgePrompt(AgentDef parent, List<Candidate> candidates, AgentRuntimeConfig runtime,
                              String userText, RouteHint hint, MaRunSpec spec) {
        return assembleJudgePrompt(parent, candidates, runtime, hint, spec).forQuestion(userText);
    }

    /**
     * 装配一轮判路由的系统提示，用户原文先占槽。
     * <p>
     * L1、种子契约、网关规则和候选名单只在这里生成一次。同一轮的多问通过
     * {@link SharedJudgePrompt} 复用这份结果，各问只替换槽里的问句。
     * 粘性说明仍由 {@code hint} 决定，多问路径传入空 hint，与原先逐问不带粘性一致。
     *
     * @param parent     编排根
     * @param candidates 可用候选
     * @param runtime    运行配置
     * @param hint       线路提示；可空
     * @param spec       方案执行来源；可空
     * @return 带用户原文槽的系统提示
     */
    SharedJudgePrompt assembleJudgePrompt(AgentDef parent, List<Candidate> candidates,
                                          AgentRuntimeConfig runtime, RouteHint hint, MaRunSpec spec) {
        StringBuilder prompt = new StringBuilder();
        // ROUTER 用途模板可能自带 {{user_query}}。先放入槽，避免严格模式因缺变量失败；
        // 真正的问句在 forQuestion 时替换，同一轮不重复装配。
        // 有方案时走 assembleRouter：模板引用与追加要求来自默认接待页（ma_def.config_json），
        // 与专家人设的 agent_def 列分开，改 Router 不会动到需求拆解等专家。
        String assembled;
        if (spec != null) {
            SystemPromptAssembler.Assembled routerPrompt =
                    prompts.assembleRouter(rootName(parent, spec), runtime, JUDGE_USER_SLOT);
            assembled = routerPrompt == null ? null : routerPrompt.text();
        } else {
            assembled = prompts.build(parent, JUDGE_USER_SLOT);
        }
        if (assembled != null && !assembled.isBlank()) {
            prompt.append(assembled).append("\n\n");
        }
        // 网关调度指令：平台内置文案，用户与方案都不能改，避免「一套方案一套口径」
        prompt.append("你是平台的调度网关：只负责把用户这一轮请求派给【可用专家】里最合适的一位，"
                + "不直接回答用户问题，也不替专家作答。\n");
        // 输出契约与 RouteDecisionParser 同口径：多意图开关只影响条数上限，不影响解析格式。
        // reason 必须随同一次判定产出：工作台智能网关步骤直接展示它，
        // 不允许事后再打一轮模型「解释一下为什么」，也不允许模板泄露内部键。
        String reasonRule = "每项都必须带一句中文 reason：说明为什么选这位（引用该专家简介里的职责，"
                + "或指出用户这句请求的关键特征），只写一句、不超过 40 字；"
                + "禁止出现 route_key、模板名、父模型、子模型、作为 AI 模型、根据系统设定等内部说法。";
        int maxN = runtime.maxRouteChildrenOrDefault();
        if (runtime.multiIntentOn()) {
            prompt.append("本次做意图路由：用户可能含多个意图。输出 JSON 数组，每项 ")
                    .append("{\"route_key\":\"...\",\"sub_query\":\"切给该子的子问题\",\"reason\":\"一句话依据\"}。")
                    .append(reasonRule)
                    .append("同一件事的先后步骤仍是一个意图，只派一位最合适的；")
                    .append("只有用户明确提出互不包含的几件事时才输出多项。")
                    .append("同 route_key 只出现一次；最多 ").append(maxN).append(" 项。")
                    .append("单意图则数组长度为 1。不要解释。都不合适时回复 []。\n");
        } else {
            prompt.append("本次只做路由选择：输出 JSON 数组且仅一项 ")
                    .append("{\"route_key\":\"...\",\"sub_query\":\"...\",\"reason\":\"一句话依据\"}，"
                            + "或只回复一个 route_key。")
                    .append(reasonRule)
                    .append("不要解释。都不合适时回复 [] 或 NONE。\n");
        }
        // 沿用线路说明放在候选列表之前：先给定上下文，再看候选，模型更容易「先认同再选择」
        if (hint != null && hint.stickyRouteKey() != null) {
            Candidate sticky = RouterCandidateLoader.byRouteKey(candidates, hint.stickyRouteKey());
            prompt.append("本会话当前线路是 ").append(hint.stickyRouteKey().trim());
            if (sticky != null) {
                prompt.append("（候选名 ").append(childLabel(sticky.agent())).append("）");
            }
            prompt.append("；若本轮仍属同一业务，请继续输出 ").append(hint.stickyRouteKey().trim())
                    .append("；明显切换业务时再输出新的 route_key。\n");
        }
        // 动态专家列表：职责简介只读单一真源（Agent 用 agent_def.description，
        // MA 候选用目标方案的 ma_def.description），不再读 ma_candidate.intent_desc
        prompt.append("\n【可用专家】（route_key｜专家｜职责简介）\n");
        for (Candidate candidate : candidates) {
            String key = candidate.route().getRouteKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            prompt.append("- ").append(key.trim()).append('｜').append(childLabel(candidate.agent()));
            String brief = RouterTexts.descriptionOf(candidate.agent());
            if (brief != null) {
                prompt.append('｜').append(brief);
            }
            // 入口才标出方案 / 专家，避免模型把方案当成又一个单兵
            if (isEntryRouter(spec)) {
                prompt.append(candidate.isMaTarget() ? "｜方案"
                        : candidate.isWorkflowTarget() ? "｜工作流" : "｜专家");
            }
            prompt.append('\n');
        }
        // 调度规则：兜底目标固定为第一位候选；默认接待里它是聊天机器人（只认 Agent，MA 目标不接管兜底）
        Candidate fallback = candidates.get(0);
        boolean receptionLabel = false;
        if (spec != null && spec.receptionDefault()) {
            // 聊天机器人已被挡下（停用 / 未授权）时不要把它写进兜底文案，否则模型会输出一个不存在的键
            Candidate assistant = spec.firstAgentCandidate()
                    .map(item -> RouterCandidateLoader.byRouteKey(candidates, item.getRouteKey()))
                    .orElse(null);
            if (assistant != null) {
                fallback = assistant;
                receptionLabel = true;
            }
        }
        String fallbackLabel = fallbackLabel(fallback, receptionLabel);
        prompt.append("\n【调度规则】\n")
                .append("1) route_key 只能取自上面的【可用专家】，不得编造，也不得指向名单外的 Agent、"
                        + "另一个 Router、方案或你自己。\n")
                .append("2) 用户只是寒暄、致谢、询问你能做什么、需求还说不清，或者没有合适的专家时，"
                        + "派给 ").append(fallbackLabel).append("。\n")
                .append("3) 用户明显换到另一类业务时，改派给对应专家；始终只输出 route_key 与子问题，不输出解释。\n");
        if (isEntryRouter(spec)) {
            prompt.append("4) 用户点名了名单中某个方案的名称时，只派给该方案，不要改派给它的成员或职责相近的单个专家。\n")
                    .append("5) 用户这一句同时包含多项交付，且某个方案的简介或成员能覆盖这些交付时，只派给该方案，"
                            + "由方案再分发；只涉及一项时可以直接派给对应专家。闲聊仍按第 2 条。\n");
        }
        return new SharedJudgePrompt(prompt.toString());
    }

    /**
     * 兜底专家的文案：默认接待明确叫「聊天机器人」，其它方案按「第一位候选」表述。
     *
     * @param fallback         第一位候选
     * @param receptionDefault 是否平台默认接待
     * @return 形如「reception_assistant（聊天机器人）」
     */
    private String fallbackLabel(Candidate fallback, boolean receptionDefault) {
        String key = fallback.route().getRouteKey() == null ? "" : fallback.route().getRouteKey().trim();
        String name = childLabel(fallback.agent());
        String label;
        if (receptionDefault) {
            // 首位候选就是聊天机器人；名字本身已是「聊天机器人」时不再重复
            label = "聊天机器人".equals(name) ? "聊天机器人" : "聊天机器人「" + name + "」";
        } else {
            label = name + "，首位候选";
        }
        return key.isEmpty() ? label : key + "（" + label + "）";
    }

    /**
     * 当前执行来源是不是工作台入口。列上的模板码为准，不看配置 JSON 里残留的串行码。
     *
     * @param spec 方案执行来源；可空
     * @return true 表示入口方案
     */
    static boolean isEntryRouter(MaRunSpec spec) {
        return spec != null && AgentRuntimeConfig.isEntryRouterTemplate(spec.template());
    }

    /**
     * 编排根展示名：方案名优先（方案就是根），旧 Agent 路径用 Agent 名。
     *
     * @param parent 旧 Agent 试跑的 ROUTER；可为 null
     * @param spec   方案来源；可空
     * @return 展示名；都为空时 null
     */
    private static String rootName(AgentDef parent, MaRunSpec spec) {
        if (spec != null) {
            return RouterObs.maRootName(spec);
        }
        return parent == null ? null : parent.getName();
    }

    /**
     * 子 Agent 显示名：名称 → 编码 → 兜底文案。实现见 {@link RouterTexts#childLabel}.
     *
     * @param agent 子 Agent；可空
     * @return 非空展示名
     */
    private static String childLabel(AgentDef agent) {
        return RouterTexts.childLabel(agent);
    }

    /**
     * 一轮判路由已经装配好的系统提示。所属域：runtime。
     * <p>
     * 用户原文只占 {@link #JUDGE_USER_SLOT}。同一轮各问替换这个槽，不再重跑 L1 与种子契约。
     * 替换值不再回扫，问句里即使带有槽字符也不会被当成占位符。
     */
    static final class SharedJudgePrompt {

        /** 已拼好的系统提示，其中用户原文仍是槽 */
        private final String text;

        /**
         * @param text 带用户原文槽的系统提示；空按空串
         */
        private SharedJudgePrompt(String text) {
            this.text = text == null ? "" : text;
        }

        /**
         * 把槽换成这一问的用户文本。
         *
         * @param userText 这一问的用户文本；空则按空串填槽
         * @return 可直接送给判定模型的系统提示
         */
        private String forQuestion(String userText) {
            String value = userText == null ? "" : userText;
            // 槽出现几次就换几次（模板与追加区都可能引用用户原文）；换进去的问句不再回扫
            return text.replace(JUDGE_USER_SLOT, value);
        }
    }

    /**
     * 同一轮多次判定共用的系统提示。所属域：runtime。
     * <p>
     * 第一次真正打模型时才装配。点名、关键字、覆盖都不触发，避免没打模型也去装一整份 ROUTER。
     * 多问路径固定传入空的线路提示，粘性说明与原先逐问不带粘性一致。
     */
    public final class TurnJudgePrompt {

        /** 本轮已装配的前缀；未打过模型时为空 */
        private SharedJudgePrompt cached;

        /**
         * 取这一问的系统提示。同一轮内前缀只装配一次。
         *
         * @param parent     编排根
         * @param candidates 可用候选
         * @param runtime    运行配置
         * @param hint       线路提示；多问路径固定为空
         * @param spec       方案执行来源
         * @param userText   这一问的用户文本
         * @return 系统提示
         */
        public String forQuestion(AgentDef parent, List<Candidate> candidates, AgentRuntimeConfig runtime,
                                  RouteHint hint, MaRunSpec spec, String userText) {
            if (cached == null) {
                cached = assembleJudgePrompt(parent, candidates, runtime, hint, spec);
            }
            return cached.forQuestion(userText);
        }
    }

    /**
     * 新建「同一轮多问共用」的提示缓存。
     *
     * @return 本轮缓存；由调用方按轮持有
     */
    public TurnJudgePrompt newTurnPrompt() {
        return new TurnJudgePrompt();
    }
}
