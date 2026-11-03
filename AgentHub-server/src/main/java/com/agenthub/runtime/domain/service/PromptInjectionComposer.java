package com.agenthub.runtime.domain.service;

import com.agenthub.common.text.TokenEstimator;
import com.agenthub.prompt.domain.model.PromptLayout;
import com.agenthub.runtime.domain.model.PromptInjectionStats;

import java.util.ArrayList;
import java.util.List;

/**
 * 按预算拼系统提示。所属域：runtime。
 * <p>
 * 段落顺序（权威口径，单测锁死；与 {@code docs/提示词工程.md} 的期望顺序一致）：
 * <pre>
 * 1. L1 平台约束                        ┐
 * 2. 平台追加句（ROUTER）                ├ 受保护 head（{@link Tier#PROTECTED}，永不裁剪）
 * 3. L2 全部锁定段（mutable=0）         ┘
 * 4. L2 全部开放段（mutable=1，含 L3 覆写）  ┐
 * 5. L3 追加区【补充要求】                   ├ 可压区（{@link Tier#SOFT_TAIL}）
 * 6. 历史业务段【业务提示词】（过渡收口）    ┘
 * 7. 记忆段：【工作记忆】/【用户偏好】
 * 8. Skill：硬触发在前，按需目录在后
 * 9. ## 工具目录
 * </pre>
 * <p>
 * 为什么记忆要在工具目录之前：模型先读到「用户偏好：答中文、常住杭州」，再看工具清单，
 * 「该不该调工具 / 调哪个」的判断才带得上偏好。原先记忆只做尾部追加，模型读完工具目录才看到偏好，
 * 记忆对工具选择的影响被显著削弱。
 * <p>
 * 裁剪只有一处决策：{@link PromptBudgetTrimmer}。本类<b>不再</b>自己按字符硬切系统提示，
 * 只负责把各段整理成「充裕写法 + 最小写法」两套文本，交给裁剪器统一按优先级让出。
 * 这样「谁先让出」只有一份实现，不会出现两处口径打架（旧实现里 Assembler 一刀、这里一刀）。
 * <p>
 * Skill 分两种注入方式：
 * <ol>
 *   <li>硬触发（triggered=true）：用户本轮输入命中了该 Skill 的触发关键词，由系统直接拼接正文，
 *       前缀固定为「用户触发了【标题】，请严格按照以下规则执行：」，不交给模型判断。
 *       它有<b>独立硬上限</b>（{@link Budgets#forcedSkillMax()}），超限截断并打标——
 *       一条几万字的强制 Skill 会把别的段落全部挤掉，比「截断后仍能执行」更糟；</li>
 *   <li>按需（triggered=false）：默认只给「标题 + 场景（简介）」，预算内再附全文，供模型自行判断是否套用。
 *       正文被让出时退回只留标题 + 场景，禁止出现没有场景说明的孤立标题。</li>
 * </ol>
 * 停用或绑定关闭的 Skill 由调用方在传入前剔除，这里不做启停判断；remark 永不进入本方法。
 * <p>
 * 工具：<b>system 里只有「名称 + 一句话」的目录</b>，完整 JSON Schema 只走模型请求的 {@code tools} 字段。
 * 禁止把 schema 再抄一份进 system——那是同一份 token 付两次钱，且两份不同步时模型会按过期 schema 调用。
 * 可调用工具必须「完整 schema 预算内 + 可注册」才进目录；禁止只留名称却当可调用。
 * 不可调用项可进目录，但必须标明本轮不可用（例如运行时明确拒注册）。
 * <p>
 * 分段计量：每段的字符数与估算 token 都写进 {@link PromptInjectionStats}，
 * 面板据此展示「钱花在哪一段」。
 */
public final class PromptInjectionComposer {

    /** 按需 Skill 注入形态。默认只给标题 + 场景，正文需显式开启才进上下文。 */
    public enum OnDemandBodyMode {
        /**
         * 默认：按需 Skill 只注入「标题 + 场景」。
         * <p>
         * 为什么这是默认：Skill 正文动辄几百上千字，8 条绑定就能吃掉整个系统提示预算；
         * 而模型真正需要的是「有哪些能力、什么时候用」，正文只在确实要执行时才值得占位。
         */
        HEADER_ONLY,
        /**
         * 在按需额度内把正文也灌进去（旧行为）。
         * <p>
         * 仅当团队确认「模型必须看到正文才会正确套用」时开启；开启后 token 会明显回升。
         */
        FIT_BUDGET
    }

    /** 空 schema 时的保守占位下限，远小于默认 tools-schema-max-chars */
    private static final int EMPTY_SCHEMA_FLOOR = 96;
    /** 可压区被裁时的标记：与其它段的文案区分，便于排障时判断是哪一档让出 */
    private static final String SYSTEM_TRUNCATED_MARK = "\n\n（系统提示已截断）";
    /** Skill 或目录被让出时的统一标记 */
    private static final String SEGMENT_TRUNCATED_MARK = "\n\n（系统提示或 Skill 已按预算截断）";
    /** 强制 Skill 正文超上限时的标记，必须与原规则正文分开，模型才知道后面被截了 */
    private static final String FORCED_SKILL_CLIPPED_MARK = "\n…（本强制 Skill 正文已按平台上限截断）";
    /** 工具目录行的一句话说明上限；只留「名称 + 一句」，完整参数走 tools 字段 */
    private static final int CATALOG_LINE_MAX = 96;
    /** Skill 段头里场景说明的上限：场景是「何时用」的判据，不是正文 */
    private static final int SKILL_SCENE_MAX = 160;

    private PromptInjectionComposer() {
    }

    /**
     * 正式链路入口：正文与结构描述一起传入，保证锁定段保护生效。
     *
     * @param assembled 组装结果（正文 + head 长度 + 各段正文），可空
     * @param memory    记忆段正文（已带【工作记忆】/【用户偏好】标记），可空
     * @param skills    已绑定且启用的 Skill
     * @param tools     已绑定工具；callable=false 仅用于运行时明确不可调的项
     * @param budgets   系统总量、按需 Skill、强制 Skill、工具 schema 四类上限
     * @return 提示词、允许注册的工具名、超预算未暴露名、注入统计
     */
    public static Outcome compose(Assembled assembled, String memory, List<SkillInput> skills,
                                  List<ToolInput> tools, Budgets budgets) {
        String system = assembled == null ? "" : assembled.text();
        PromptLayout layout = assembled == null || assembled.layout() == null
                ? PromptLayout.EMPTY : assembled.layout();
        return compose(system, layout, memory, skills, tools, budgets);
    }

    /**
     * 兼容入口：调用方只有一段拼好的系统提示（配置预览、老测试），没有结构信息。
     * 此时 headChars=0，等同于整段都可裁——正式链路必须用带 {@link PromptLayout} 的重载。
     *
     * @param system  已带平台前缀的系统提示
     * @param skills  已绑定且启用的 Skill
     * @param tools   已绑定工具
     * @param budgets 预算
     * @return 提示词、允许注册的工具名、超预算未暴露名、注入统计
     */
    public static Outcome compose(String system, List<SkillInput> skills, List<ToolInput> tools, Budgets budgets) {
        return compose(new Assembled(system, PromptLayout.EMPTY), null, skills, tools, budgets);
    }

    /**
     * 主实现：先把各段整理成「充裕 / 最小」两套文本，再交给
     * {@link PromptBudgetTrimmer} 一次性按优先级让出。
     *
     * @param system  组装正文（受保护 head 在前，可截断 tail 在后）
     * @param layout  结构描述；headChars 是裁剪下界，0 表示无结构信息
     * @param memory  记忆段正文，可空
     * @param skills  绑定 Skill
     * @param tools   绑定工具
     * @param budgets 预算
     * @return 拼接结果
     */
    public static Outcome compose(String system, PromptLayout layout, String memory,
                                  List<SkillInput> skills, List<ToolInput> tools, Budgets budgets) {
        // ---- 一、工具 schema：独立专款，按绑定序贪心塞满，不吃 Skill/系统段的额度 ----
        SchemaPlan schema = planSchemas(tools, budgets.schemaMax());

        // ---- 二、切分受保护 head 与可压 tail。head 是红线，绝不进裁剪器 ----
        String base = system == null ? "" : system;
        int headChars = Math.min(Math.max(layout.headChars(), 0), base.length());
        String head = base.substring(0, headChars);
        String tail = base.substring(headChars);

        // ---- 三、Skill：强制块先按硬上限裁，按需块默认只留标题 + 场景 ----
        HardSkillPlan hard = planHardSkills(skills, budgets.forcedSkillMax());
        OptionalSkillPlan optional = planOptionalSkills(skills, budgets.skillMax(), hard.chars(),
                budgets.onDemandBodyMode());

        // ---- 四、工具目录：可调用项带一句话，不可调用项标「本轮不可用」 ----
        String catalog = renderCatalog(schema.lines(), CATALOG_LINE_MAX);
        String catalogNamesOnly = renderCatalogNamesOnly(schema.lines());

        // ---- 五、组装成带优先级的段序列（顺序即出站顺序，不可调换） ----
        List<PromptBudgetTrimmer.Section> sections = new ArrayList<>();
        sections.add(PromptBudgetTrimmer.Section.fixed("protected", PromptBudgetTrimmer.Tier.PROTECTED, head));
        if (!tail.isEmpty()) {
            // 可压区只按字符从尾部裁，不整段丢弃：用户配置的业务段常常只多出几十字
            sections.add(PromptBudgetTrimmer.Section.fixed("soft", PromptBudgetTrimmer.Tier.SOFT_TAIL, tail));
        }
        String memoryText = memory == null ? "" : memory.trim();
        if (!memoryText.isEmpty()) {
            sections.add(new PromptBudgetTrimmer.Section("memory", PromptBudgetTrimmer.Tier.MEMORY,
                    "\n\n" + memoryText, ""));
        }
        sections.addAll(hard.sections());
        sections.addAll(optional.sections());
        if (!catalog.isEmpty()) {
            sections.add(new PromptBudgetTrimmer.Section("catalog", PromptBudgetTrimmer.Tier.CATALOG,
                    catalog, catalogNamesOnly));
        }

        // ---- 六、唯一一刀：按「记忆 → 按需 Skill → 目录文案 → 可压区 → 强制 Skill」让出 ----
        PromptBudgetTrimmer.Outcome trimmed = PromptBudgetTrimmer.trim(sections, budgets.systemMax());

        String prompt = trimmed.text();
        boolean softCut = trimmed.yielded().contains("soft");
        boolean promptSegmentTruncated = trimmed.truncated()
                || optional.truncated()
                || schema.anyLineTrimmed()
                || hard.clipped() > 0;
        // 可压区被裁要留痕：否则用户只看到「业务提示词少了一段」却不知道是被预算吃掉的
        if (softCut) {
            prompt = prompt + SYSTEM_TRUNCATED_MARK;
        }

        // ---- 七、页脚：按让出类型区分，避免「其它段截断」误写成工具未暴露 ----
        if (schema.budgetHit()) {
            prompt = prompt + "\n\n（超预算可调用工具未对本轮暴露）";
        } else if (promptSegmentTruncated && prompt.indexOf("已截断") < 0) {
            prompt = prompt + SEGMENT_TRUNCATED_MARK;
        }

        return new Outcome(prompt, schema.schemaNames(), schema.omittedCallable(),
                statsOf(trimmed, hard, optional.count(), schema, promptSegmentTruncated));
    }

    /**
     * 汇总分段计量。字符数按最终文本实测，token 走 {@link TokenEstimator} 估算。
     * <p>
     * 为什么 token 要按段分开算而不是最后乘一个系数：中文与英文段的字/token 比差 4 倍，
     * 一个总系数会让「目录文案其实很便宜、强制 Skill 很贵」这类结论完全失真。
     * <p>
     * 长度一律读裁剪器返回的最终段文本，而不是规划时的文本：让出之后各段真实长度才是记账依据，
     * 拿规划值会把「已让出的记忆」仍算在总额里，面板就会显示一个不存在的开销。
     *
     * @param trimmed                裁剪结果（含各段最终文本）
     * @param hard                   强制 Skill 计划
     * @param optionalCount          按需 Skill 个数
     * @param schema                 工具 schema 计划
     * @param promptSegmentTruncated 非工具额度类截断
     * @return 分段统计
     */
    private static PromptInjectionStats statsOf(PromptBudgetTrimmer.Outcome trimmed, HardSkillPlan hard,
                                                int optionalCount, SchemaPlan schema,
                                                boolean promptSegmentTruncated) {
        PromptInjectionStats stats = new PromptInjectionStats();
        int head = 0;
        int tail = 0;
        int memory = 0;
        int forced = 0;
        int optional = 0;
        int catalog = 0;
        for (PromptBudgetTrimmer.Section section : trimmed.sections()) {
            int length = section.text().length();
            switch (section.tier()) {
                case PROTECTED -> head += length;
                case SOFT_TAIL -> tail += length;
                // 记忆段进段时带了两个换行分隔符，统计只算正文
                case MEMORY -> memory += Math.max(0, length - 2);
                case FORCED_SKILL -> forced += length;
                case OPTIONAL_SKILL -> optional += length;
                case CATALOG -> catalog += length;
            }
        }
        stats.setSystemChars(head + tail);
        stats.setProtectedChars(head);
        stats.setSystemSoftChars(tail);
        stats.setProtectedTokens(TokenEstimator.estimateChars(head));
        stats.setSystemSoftTokens(TokenEstimator.estimateChars(tail));

        stats.setMemoryChars(memory);
        stats.setMemoryTokens(TokenEstimator.estimateChars(memory));

        stats.setSkillsHardTriggered(hard.count());
        stats.setForcedSkillsClipped(hard.clipped());
        stats.setSkillRequiredChars(forced);
        stats.setSkillRequiredTokens(TokenEstimator.estimateChars(forced));
        stats.setSkillOptionalChars(optional);
        stats.setSkillOptionalTokens(TokenEstimator.estimateChars(optional));
        stats.setSkillChars(forced + optional);
        stats.setSkillsInjected(hard.count() + optionalCount);

        stats.setToolCatalogChars(catalog);
        stats.setToolCatalogTokens(TokenEstimator.estimateChars(catalog));
        stats.setToolSchemaChars(schema.chars());
        stats.setToolSchemaTokens(TokenEstimator.estimateChars(schema.chars()));
        stats.setToolsInjected(schema.lines().size());

        stats.setTruncated(trimmed.truncated() || schema.budgetHit() || promptSegmentTruncated);
        stats.setPromptSegmentTruncated(promptSegmentTruncated);
        stats.setToolSchemaBudgetHit(schema.budgetHit());
        return stats;
    }

    /**
     * 按绑定序分配工具 schema 预算：塞得下就整份放行并可注册，塞不下就本轮不暴露。
     * 空 schema 按占位成本记账，避免「额度还在却 0 工具」。
     *
     * @param tools     绑定工具，可空
     * @param schemaMax schema 专款上限
     * @return 目录行、可注册名、未暴露名与记账字符数
     */
    private static SchemaPlan planSchemas(List<ToolInput> tools, int schemaMax) {
        int chars = 0;
        boolean budgetHit = false;
        List<String> schemaNames = new ArrayList<>();
        List<String> omitted = new ArrayList<>();
        List<CatalogLine> lines = new ArrayList<>();
        for (ToolInput tool : tools == null ? List.<ToolInput>of() : tools) {
            String name = clean(tool.name());
            String oneLiner = clean(tool.oneLiner());
            String schema = tool.schema() == null ? "" : tool.schema().trim();
            // 不可调用：可暴露名称，但必须写明不可用，避免模型当可调工具
            if (!tool.callable()) {
                lines.add(new CatalogLine(name, oneLiner, false, false));
                continue;
            }
            int cost = schemaCost(name, oneLiner, schema);
            if (chars + cost <= Math.max(schemaMax, 0)) {
                schemaNames.add(name);
                chars += cost;
                lines.add(new CatalogLine(name, oneLiner, true, false));
            } else {
                omitted.add(name);
                budgetHit = true;
            }
        }
        return new SchemaPlan(schemaNames, omitted, lines, chars, budgetHit);
    }

    /**
     * 强制 Skill：每条独立按硬上限裁，段头（标题 + 场景）与固定前缀不裁。
     * <p>
     * 为什么段头不裁：模型需要知道「这是被系统强制执行的哪条规则」，标题和场景是它唯一的判据；
     * 把标题也截掉，剩下的正文就成了没有出处的杂文。
     *
     * @param skills        绑定 Skill，可空
     * @param forcedSkillMax 单条强制块上限；≤0 表示不限
     * @return 段列表与计数
     */
    private static HardSkillPlan planHardSkills(List<SkillInput> skills, int forcedSkillMax) {
        List<PromptBudgetTrimmer.Section> sections = new ArrayList<>();
        int chars = 0;
        int count = 0;
        int clipped = 0;
        for (SkillInput skill : skills == null ? List.<SkillInput>of() : skills) {
            if (!skill.triggered()) {
                continue;
            }
            String header = skillHeader("强制 Skill ", skill);
            String prefix = "\n用户触发了【" + clean(skill.title()) + "】，请严格按照以下规则执行：\n";
            String body = skill.full() == null ? "" : skill.full().trim();
            String block = header + prefix + body;
            // 硬上限只掐正文：段头与前缀必须完整，否则模型看不出这是哪条强制规则
            if (forcedSkillMax > 0 && block.length() > forcedSkillMax) {
                int room = Math.max(0, forcedSkillMax - header.length() - prefix.length()
                        - FORCED_SKILL_CLIPPED_MARK.length());
                block = header + prefix + clip(body, room) + FORCED_SKILL_CLIPPED_MARK;
                clipped++;
            }
            sections.add(PromptBudgetTrimmer.Section.fixed("skill-forced:" + clean(skill.title()),
                    PromptBudgetTrimmer.Tier.FORCED_SKILL, block));
            chars += block.length();
            count++;
        }
        return new HardSkillPlan(sections, chars, count, clipped);
    }

    /**
     * 按需 Skill：默认只给「标题 + 场景」，剩余额度内附正文；正文放不下时退回段头。
     *
     * @param skills   绑定 Skill，可空
     * @param skillMax 按需 Skill 的额度（不含强制块）
     * @param hardChars 强制块已占字符
     * @return 段列表与计数
     */
    /**
     * 按需 Skill：<b>先把全部段头（标题 + 场景）预留出来</b>；正文是否灌入由
     * {@link OnDemandBodyMode} 决定。
     * <p>
     * 为什么要先预留段头：一轮贪心时，前面 Skill 的长正文会把额度吃光，后面的 Skill 连段头都放不下——
     * 模型看到「有的技能有说明、有的完全消失」，而它其实有 8 条绑定。
     * 段头是「何时该用」的唯一判据，必须优先保证全部在场。
     * <p>
     * 默认 {@link OnDemandBodyMode#HEADER_ONLY}：按需 Skill 只给标题 + 场景。
     * 这是 P0 预算收敛的主要来源——旧实现把 8 条正文全灌进 system，
     * 单 Skill 段就能吃掉五千多字。
     *
     * @param skills    绑定 Skill，可空
     * @param skillMax  按需 Skill 的额度（不含强制块）
     * @param hardChars 强制块已占字符
     * @param mode      正文注入形态
     * @return 段列表与计数
     */
    private static OptionalSkillPlan planOptionalSkills(List<SkillInput> skills, int skillMax, int hardChars,
                                                        OnDemandBodyMode mode) {
        List<SkillInput> bound = new ArrayList<>();
        for (SkillInput skill : skills == null ? List.<SkillInput>of() : skills) {
            if (!skill.triggered()) {
                bound.add(skill);
            }
        }
        int room = Math.max(0, skillMax - hardChars);
        int headerTotal = 0;
        List<String> headers = new ArrayList<>(bound.size());
        for (SkillInput skill : bound) {
            String header = skillHeader("Skill ", skill);
            headers.add(header);
            headerTotal += header.length();
        }
        // HEADER_ONLY：段头就是最终形态。段头总量即使超过额度也全部保留——
        // 让模型知道能力存在，比让它彻底看不见更好
        boolean fitBody = mode == OnDemandBodyMode.FIT_BUDGET;
        int remaining = fitBody ? room - headerTotal : 0;
        List<PromptBudgetTrimmer.Section> sections = new ArrayList<>(bound.size());
        int used = 0;
        boolean truncated = false;
        for (int i = 0; i < bound.size(); i++) {
            SkillInput skill = bound.get(i);
            String header = headers.get(i);
            String body = skill.full() == null ? "" : skill.full().trim();
            String id = "skill-on-demand:" + clean(skill.title());
            boolean injectBody = fitBody && !body.isEmpty() && remaining > 0 && body.length() <= remaining;
            if (injectBody) {
                // 正文放得下：仍然保留「最小写法」，总量超预算时裁剪器还能把它退成段头
                sections.add(new PromptBudgetTrimmer.Section(id, PromptBudgetTrimmer.Tier.OPTIONAL_SKILL,
                        header + "\n" + body, header));
                remaining -= body.length();
                used += header.length() + body.length();
            } else {
                // 默认形态或额度不足：只有段头（标题 + 场景）
                sections.add(PromptBudgetTrimmer.Section.fixed(id,
                        PromptBudgetTrimmer.Tier.OPTIONAL_SKILL, header));
                used += header.length();
                // 只在「本该灌正文但没灌成」时标记截断，避免 HEADER_ONLY 常态下满屏截断告警
                if (fitBody && !body.isEmpty()) {
                    truncated = true;
                }
            }
        }
        return new OptionalSkillPlan(sections, used, sections.size(), truncated);
    }
    /**
     * 渲染工具目录。可调用行过长只裁一句话说明；不可调用行保持完整，以免丢掉「本轮不可用」。
     * <p>
     * 只写「名称 + 一句话」：参数结构在 {@code tools} 字段里，重复进 system 等于同一份 token 付两次钱。
     *
     * @param lines 目录行
     * @param max   单行上限
     * @return 目录正文（含段头）；无工具时为空串
     */
    private static String renderCatalog(List<CatalogLine> lines, int max) {
        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder catalog = new StringBuilder("\n\n## 工具目录");
        for (CatalogLine line : lines) {
            String row = catalogRow(line, max);
            catalog.append(row);
            if (line.callable() && row.length() < rawCatalogRow(line).length()) {
                line.markTrimmed();
            }
        }
        return catalog.toString();
    }

    /**
     * 只保留「名称 + 不可用标记」的精简目录：可调用工具名必须留下，
     * 否则模型看不到能力却仍拿到 schema，等于凭空多出工具。
     *
     * @param lines 目录行
     * @return 精简目录（含段头）；无工具时为空串
     */
    private static String renderCatalogNamesOnly(List<CatalogLine> lines) {
        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("\n\n## 工具目录");
        for (CatalogLine line : lines) {
            text.append("\n- ").append(line.name());
            if (!line.callable()) {
                text.append("（本轮不可用）");
            }
        }
        return text.toString();
    }

    private static String rawCatalogRow(CatalogLine line) {
        return "\n- " + line.name() + "：" + line.oneLiner() + (line.callable() ? "" : "（本轮不可用）");
    }

    private static String catalogRow(CatalogLine line, int max) {
        String row = rawCatalogRow(line);
        if (line.callable() && row.length() > max) {
            return row.substring(0, max) + "…";
        }
        return row;
    }

    /**
     * Skill 段头：标题 + 场景（description）。
     * 场景为空时不留「场景：」空标签——那是残缺注入，模型只会看到没有说明的标题；
     * 改为显式写「未填写简介」，提示模型此 Skill 的判断依据缺失。
     * 场景过长按 {@link #SKILL_SCENE_MAX} 截断：场景是「何时用」的判据，不是正文。
     *
     * @param kind  段类型前缀，如「Skill 」或「强制 Skill 」
     * @param skill 该 Skill 输入
     * @return 以两个换行开头的段头（接在上文之后）
     */
    private static String skillHeader(String kind, SkillInput skill) {
        String scene = clean(skill.scene());
        if (scene.isEmpty()) {
            scene = "（未填写简介）";
        } else if (scene.length() > SKILL_SCENE_MAX) {
            scene = scene.substring(0, SKILL_SCENE_MAX) + "…";
        }
        return "\n\n## " + kind + clean(skill.title()) + "\n场景：" + scene;
    }

    /**
     * 可调用工具占用的 schema 预算。库表为空时用占位成本，避免额度≥0 却全部省略。
     *
     * @param name     调用名
     * @param oneLiner 一句话
     * @param schema   DB 中的 JSON Schema，可空
     * @return 计入 tools-schema 预算的字符数
     */
    static int schemaCost(String name, String oneLiner, String schema) {
        if (schema != null && !schema.isBlank()) {
            return schema.length();
        }
        return Math.max(EMPTY_SCHEMA_FLOOR, clean(name).length() + clean(oneLiner).length() + 64);
    }

    /**
     * @param raw 原文
     * @return 去掉首尾空白；null 变空串
     */
    private static String clean(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /**
     * @param body 正文
     * @param room 可用字符；≤0 时返回空串
     * @return 截断后的正文
     */
    private static String clip(String body, int room) {
        if (room <= 0 || body.isEmpty()) {
            return "";
        }
        return body.length() <= room ? body : body.substring(0, room);
    }

    /**
     * 目录行。trimmed 是可变标记：记录该行一句话是否被裁过，供统计与页脚判断。
     *
     * @param name     调用名
     * @param oneLiner 一句话
     * @param callable 是否本轮可注册调用；false 时目录行会标「本轮不可用」
     * @param trimmed  一句话是否被裁剪
     */
    private static final class CatalogLine {
        private final String name;
        private final String oneLiner;
        private final boolean callable;
        private boolean trimmed;

        private CatalogLine(String name, String oneLiner, boolean callable, boolean trimmed) {
            this.name = name;
            this.oneLiner = oneLiner;
            this.callable = callable;
            this.trimmed = trimmed;
        }

        private String name() {
            return name;
        }

        private String oneLiner() {
            return oneLiner;
        }

        private boolean callable() {
            return callable;
        }

        private void markTrimmed() {
            this.trimmed = true;
        }
    }

    /**
     * @param schemaNames       预算内可注册的工具名
     * @param omittedCallable   因 schema 超预算而未暴露的可调用工具名
     * @param lines             目录行
     * @param chars             schema 记账字符数
     * @param budgetHit         是否有可调用工具被 schema 预算挡下
     */
    private record SchemaPlan(List<String> schemaNames, List<String> omittedCallable, List<CatalogLine> lines,
                              int chars, boolean budgetHit) {

        /**
         * @return 是否有可调用行的说明被裁剪（用于统计页脚）
         */
        private boolean anyLineTrimmed() {
            return lines.stream().anyMatch(line -> line.callable() && line.trimmed);
        }
    }

    /**
     * @param sections 强制 Skill 段（顺序即出站顺序）
     * @param chars    强制块合计字符
     * @param count    强制 Skill 个数
     * @param clipped  被硬上限截断正文的个数
     */
    private record HardSkillPlan(List<PromptBudgetTrimmer.Section> sections, int chars, int count, int clipped) {
    }

    /**
     * @param sections  按需 Skill 段
     * @param used      规划时已占字符
     * @param count     按需 Skill 个数
     * @param truncated 是否有正文因额度不足未注入
     */
    private record OptionalSkillPlan(List<PromptBudgetTrimmer.Section> sections, int used, int count,
                                     boolean truncated) {
    }

    /**
     * 组装正文与结构描述一起传入，避免「有正文没结构」导致锁定段保护失效。
     *
     * @param text   组装正文（head + tail 已拼好）
     * @param layout 结构描述，可空（空表示无结构信息）
     */
    public record Assembled(String text, PromptLayout layout) {
    }

    /**
     * 预算。四类上限各管一段，互不吃对方的额度。
     *
     * @param systemMax      系统提示总量上限（保护区 + 可压区 + 记忆 + Skill + 目录）；
     *                       保护区永不裁剪，因此实际可能略超
     * @param skillMax       按需 Skill 额度（强制块不吃这个上限）
     * @param schemaMax      完整 JSON Schema 专款（走 tools 字段，与 system 分计）
     * @param forcedSkillMax 单条强制 Skill 块硬上限；≤0 表示不限
     * @param onDemandBodyMode 按需 Skill 是否灌正文；默认只给标题 + 场景
     */
    public record Budgets(int systemMax, int skillMax, int schemaMax, int forcedSkillMax,
                          OnDemandBodyMode onDemandBodyMode) {

        /**
         * 兼容构造：未配置按需正文形态时按默认（只给标题 + 场景）。
         *
         * @param systemMax      系统提示总量上限
         * @param skillMax       按需 Skill 额度
         * @param schemaMax      schema 专款
         * @param forcedSkillMax 单条强制 Skill 硬上限
         */
        public Budgets(int systemMax, int skillMax, int schemaMax, int forcedSkillMax) {
            this(systemMax, skillMax, schemaMax, forcedSkillMax, OnDemandBodyMode.HEADER_ONLY);
        }

        /**
         * 兼容构造：未配置强制 Skill 上限时给保守默认值。
         *
         * @param systemMax 系统提示总量上限
         * @param skillMax  按需 Skill 额度
         * @param schemaMax schema 专款
         */
        public Budgets(int systemMax, int skillMax, int schemaMax) {
            this(systemMax, skillMax, schemaMax, DEFAULT_FORCED_SKILL_MAX, OnDemandBodyMode.HEADER_ONLY);
        }

        /**
         * 紧凑构造：补默认正文形态。record 的规范构造器参数为 null 时按默认处理。
         */
        public Budgets {
            onDemandBodyMode = onDemandBodyMode == null ? OnDemandBodyMode.HEADER_ONLY : onDemandBodyMode;
        }

        /** 单条强制 Skill 的默认硬上限：约等于 1500 个中文字的正文 */
        public static final int DEFAULT_FORCED_SKILL_MAX = 1800;
    }

    /**
     * 一段 Skill 的注入输入。
     *
     * @param title     标题，进目录与强制块标题
     * @param scene     场景说明，取自 description（供模型判断何时加载）；禁止放 remark
     * @param full      全文，可空
     * @param triggered 是否被触发关键词硬命中：true 时全文强制注入，且前缀固定为「用户触发了【标题】…」
     */
    public record SkillInput(String title, String scene, String full, boolean triggered) {

        /**
         * 按需注入（默认）：模型按标题 + 场景自行判断是否套用。
         *
         * @param title 标题
         * @param scene 场景
         * @param full  全文
         */
        public SkillInput(String title, String scene, String full) {
            this(title, scene, full, false);
        }
    }

    /**
     * @param name     调用名
     * @param oneLiner 一句话
     * @param schema   JSON Schema。BUILTIN 应由调用方传入 Java 元数据；仍可空，空则走占位成本
     * @param callable 能否在本轮注册成真正可调用的工具
     */
    public record ToolInput(String name, String oneLiner, String schema, boolean callable) {
    }

    /**
     * @param prompt               系统提示
     * @param schemaNames          预算内可注册、可调用的工具名
     * @param omittedCallableNames 因工具 schema 预算不足而未暴露的可调用工具名
     * @param stats                注入统计（含分段字符与估算 token）
     */
    public record Outcome(String prompt, List<String> schemaNames, List<String> omittedCallableNames,
                          PromptInjectionStats stats) {
    }
}
