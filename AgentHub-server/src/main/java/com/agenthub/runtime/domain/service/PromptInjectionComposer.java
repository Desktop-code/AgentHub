package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.PromptInjectionStats;

import java.util.ArrayList;
import java.util.List;

/**
 * 按预算拼系统提示。所属域：runtime。
 * 顺序：先按绑定序占用「工具 schema」独立预算（可调用才进目录），再灌 Skill，最后拼平台前缀/业务段已截断的 base。
 * 可调用工具必须「完整 schema 预算内 + 可注册」才进目录；禁止只留名称却当可调用。
 * 调用方应对 BUILTIN 传入 Java 注解生成的 schema。这里仍对空 schema 按占位成本记账，禁止「额度仍在却 0 工具」。
 * 不可调用项可进目录，但必须标明本轮不可用（例如运行时明确拒注册）。
 * MCP 与 BUILTIN 若均为可调用，走同一套 schema 预算与绑定序。
 * 未放进入参列表的工具不会出现，因此未绑定的工具不会进提示词。
 *
 * <p>Skill 分两种注入方式：
 * <ol>
 *   <li>硬触发（triggered=true）：用户本轮输入命中了该 Skill 的触发关键词，由系统直接拼接全文，
 *       前缀固定为「用户触发了【标题】，请严格按照以下规则执行：」，不交给模型判断；
 *       硬触发块排在最前，优先占用 Skill 段，保证不被按需目录挤掉。</li>
 *   <li>按需（triggered=false）：先给「标题 + 场景（简介）」，预算内再附全文，供模型自行判断是否套用。
 *       预算不足时退回只留标题 + 场景，禁止出现没有场景说明的孤立标题。</li>
 * </ol>
 * 停用或绑定关闭的 Skill 由调用方在传入前剔除，这里不做启停判断；remark 永不进入本方法。
 */
public final class PromptInjectionComposer {

    /** 空 schema 时的保守占位下限，远小于默认 tools-schema-max-chars */
    private static final int EMPTY_SCHEMA_FLOOR = 96;

    private PromptInjectionComposer() {
    }

    /**
     * @param system  已经带平台前缀的系统提示
     * @param skills  已绑定且启用的 Skill
     * @param tools   已绑定工具；callable=false 仅用于运行时明确不可调的项
     * @param budgets 三类字符上限；工具 schema 与 Skill/系统段分计，工具额度不因其它段截断被吃光
     * @return 提示词、允许注册的工具名、超预算未暴露名、注入统计
     */
    public static Outcome compose(String system, List<SkillInput> skills, List<ToolInput> tools, Budgets budgets) {
        boolean promptSegmentTruncated = false;
        boolean toolSchemaBudgetHit = false;

        // 先占工具 schema 预算：按绑定顺序能完整塞几个塞几个，与 Skill/系统段互不挪用
        int schemaChars = 0;
        List<String> schemaNames = new ArrayList<>();
        List<String> omittedCallable = new ArrayList<>();
        List<CatalogLine> lines = new ArrayList<>();
        List<ToolInput> bound = tools == null ? List.of() : tools;
        for (ToolInput tool : bound) {
            String name = clean(tool.name());
            String oneLiner = clean(tool.oneLiner());
            String schema = tool.schema() == null ? "" : tool.schema().trim();
            if (!tool.callable()) {
                // 不可调用：可暴露名称，但必须写明不可用，避免模型当可调工具
                lines.add(new CatalogLine(name, oneLiner, false));
                continue;
            }
            int cost = schemaCost(name, oneLiner, schema);
            if (schemaChars + cost <= budgets.schemaMax()) {
                schemaNames.add(name);
                schemaChars += cost;
                lines.add(new CatalogLine(name, oneLiner, true));
            } else {
                // 仅当本工具成本确实塞不进剩余额度时才省略；空 schema 占位很小，默认预算下不会误杀
                omittedCallable.add(name);
                toolSchemaBudgetHit = true;
            }
        }

        String base = system == null ? "" : system;
        if (base.length() > budgets.systemMax()) {
            base = base.substring(0, Math.max(budgets.systemMax(), 0)) + "\n\n（系统提示已截断）";
            promptSegmentTruncated = true;
        }

        StringBuilder skillText = new StringBuilder();
        int skillChars = 0;
        int skillsInjected = 0;
        int skillsHardTriggered = 0;
        List<SkillInput> boundSkills = skills == null ? List.of() : skills;

        // 第一轮：硬触发。用户输入命中的 Skill 由系统强制注入全文，不参与按需预算的贪心分配，
        // 否则「先绑的按需 Skill 吃满预算」会让强制规则整段丢失，等于没触发。
        // 段头同样带场景（description）：硬触发的 Skill 仍是「已绑定且启用」，目录要求 title + description 一并成立。
        for (SkillInput skill : boundSkills) {
            if (!skill.triggered()) {
                continue;
            }
            String block = skillHeader("强制 Skill ", skill)
                    + "\n用户触发了【" + clean(skill.title()) + "】，请严格按照以下规则执行：\n"
                    + (skill.full() == null ? "" : skill.full().trim());
            skillText.append(block);
            skillChars += block.length();
            skillsInjected++;
            skillsHardTriggered++;
        }

        // 第二轮：按需目录。剩余额度 = 总预算 − 已用的强制注入；用尽时只剩标题+场景，
        // 仍保证模型能按 description 判断，禁止只留一个没有说明的标题。
        int onDemandRoom = Math.max(0, budgets.skillMax() - skillChars);
        int onDemandChars = 0;
        for (SkillInput skill : boundSkills) {
            if (skill.triggered()) {
                continue;
            }
            String summary = skillHeader("Skill ", skill);
            String full = skill.full() == null ? "" : skill.full().trim();
            String block = full.isEmpty() ? summary : summary + "\n" + full;
            if (onDemandChars + block.length() <= onDemandRoom) {
                skillText.append(block);
                onDemandChars += block.length();
                skillChars += block.length();
                skillsInjected++;
                continue;
            }
            if (!full.isEmpty() && onDemandChars + summary.length() <= onDemandRoom) {
                skillText.append(summary);
                onDemandChars += summary.length();
                skillChars += summary.length();
                skillsInjected++;
                promptSegmentTruncated = true;
                continue;
            }
            promptSegmentTruncated = true;
            break;
        }

        StringBuilder catalog = new StringBuilder();
        int catalogChars = 0;
        int toolsInjected = 0;
        if (!lines.isEmpty()) {
            catalog.append("\n\n## 工具目录");
            catalogChars += "\n\n## 工具目录".length();
        }
        for (CatalogLine line : lines) {
            String row = "\n- " + line.name() + "：" + line.oneLiner();
            if (!line.callable()) {
                row = row + "（本轮不可用）";
            }
            if (row.length() > 180) {
                row = row.substring(0, 180);
                promptSegmentTruncated = true;
            }
            catalog.append(row);
            catalogChars += row.length();
            toolsInjected++;
        }
        // 页脚按截断类型区分，避免「其它段截断」误写成工具未暴露
        if (toolSchemaBudgetHit) {
            catalog.append("\n\n（超预算可调用工具未对本轮暴露）");
        } else if (promptSegmentTruncated && skillText.indexOf("已截断") < 0) {
            catalog.append("\n\n（系统提示或 Skill 已按预算截断）");
        }

        PromptInjectionStats stats = new PromptInjectionStats();
        stats.setSystemChars(base.length());
        stats.setSkillChars(skillChars);
        stats.setToolCatalogChars(catalogChars);
        stats.setToolSchemaChars(schemaChars);
        stats.setSkillsInjected(skillsInjected);
        stats.setSkillsHardTriggered(skillsHardTriggered);
        stats.setToolsInjected(toolsInjected);
        stats.setTruncated(promptSegmentTruncated || toolSchemaBudgetHit);
        stats.setPromptSegmentTruncated(promptSegmentTruncated);
        stats.setToolSchemaBudgetHit(toolSchemaBudgetHit);
        return new Outcome(base + skillText + catalog, schemaNames, omittedCallable, stats);
    }

    /**
     * Skill 段头：标题 + 场景（description）。
     * 场景为空时不留「场景：」空标签——那是残缺注入，模型只会看到没有说明的标题；
     * 改为显式写「未填写简介」，提示模型此 Skill 的判断依据缺失。
     *
     * @param kind  段类型前缀，如「Skill 」或「强制 Skill 」
     * @param skill 该 Skill 输入
     * @return 以两个换行开头的段头（接在上文之后）
     */
    private static String skillHeader(String kind, SkillInput skill) {
        String scene = clean(skill.scene());
        String sceneLine = scene.isEmpty() ? "（未填写简介）" : scene;
        return "\n\n## " + kind + clean(skill.title()) + "\n场景：" + sceneLine;
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
     * @param name     调用名
     * @param oneLiner 一句话
     * @param callable 是否本轮可注册调用；false 时目录行会标「本轮不可用」
     */
    private record CatalogLine(String name, String oneLiner, boolean callable) {
    }

    private static String clean(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /**
     * @param systemMax 平台前缀加业务段
     * @param skillMax  Skill 正文
     * @param schemaMax 完整 JSON Schema（与 system/skill 分计，专款专用）
     */
    public record Budgets(int systemMax, int skillMax, int schemaMax) {
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
     * @param stats                注入统计（含分段截断标记）
     */
    public record Outcome(String prompt, List<String> schemaNames, List<String> omittedCallableNames,
                          PromptInjectionStats stats) {
    }
}
