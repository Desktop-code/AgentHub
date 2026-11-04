package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentSkillBind;
import com.agenthub.agent.domain.model.AgentToolBind;
import com.agenthub.agent.domain.repository.AgentSkillBindRepository;
import com.agenthub.agent.domain.repository.AgentToolBindRepository;
import com.agenthub.agent.infra.PromptBudgetProperties;
import com.agenthub.prompt.domain.model.PromptLayout;
import com.agenthub.runtime.domain.model.BuiltinToolSpec;
import com.agenthub.runtime.domain.model.McpToolSpec;
import com.agenthub.runtime.domain.model.PromptInjectionStats;
import com.agenthub.runtime.domain.service.BuiltinSchemaSource;
import com.agenthub.runtime.domain.service.PromptInjectionComposer;
import com.agenthub.runtime.domain.service.ToolEnablement;
import com.agenthub.skill.domain.model.SkillDef;
import com.agenthub.skill.domain.repository.SkillDefRepository;
import com.agenthub.skill.domain.service.SkillTriggerMatcher;
import com.agenthub.tool.domain.model.ToolItem;
import com.agenthub.tool.domain.model.ToolSource;
import com.agenthub.tool.domain.model.ToolSourceType;
import com.agenthub.tool.domain.repository.ToolItemRepository;
import com.agenthub.tool.domain.repository.ToolSourceRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 按预算组装系统提示和可调用工具。所属域：runtime。
 * <p>
 * 段落顺序固定为「L1 与锁定段（受保护）→ 开放段与 L3 → 记忆 → Skill → 工具目录」，
 * 由 {@link SystemPromptAssembler} 产出前半段、{@link PromptInjectionComposer} 续拼后半段，本类只做编排。
 * <p>
 * Skill 目录始终带 title + description（场景），供模型判断何时加载；正文只在预算内灌入。
 * 触发关键词命中时改为强制注入：该 Skill 全文带固定前缀直接进上下文，不经模型决定。
 * 可调用工具要么完整 schema 进预算并注册，要么本轮不暴露；禁止「仅留名当可调用」。
 * BUILTIN 的 schema 以 Java {@code @Tool} 为准，库表空或旧文不削弱约束；Java 没有实现时才退回库表。
 * MCP 与 BUILTIN 同一套 tools-schema 预算、同一绑定序；MCP 用库表 input_schema_json（可空占位）。
 * 只遍历绑定表，未绑定的工具不会进提示词。
 * 停用即不进 schema：工具源停用、工具项停用、绑定停用，任一成立都在这里排除；
 * 判据统一走 {@link ToolEnablement}，与执行侧注册同一条件，禁止「schema 全量、执行再 skip」。
 * Skill 同理：绑定关或 Skill 停用既不进目录、也不参与硬触发；remark 永不注入。
 */
@Component
public class TrialPromptFactory {

    private final AgentToolBindRepository toolBinds;
    private final AgentSkillBindRepository skillBinds;
    private final ToolItemRepository items;
    private final ToolSourceRepository sources;
    private final SkillDefRepository skills;
    private final SystemPromptAssembler prompts;
    private final PromptBudgetProperties budgets;
    private final BuiltinSchemaSource builtinSchemas;

    /**
     * @param toolBinds      工具绑定
     * @param skillBinds     Skill 绑定
     * @param items          工具项
     * @param sources        区分 BUILTIN 和 MCP
     * @param skills         Skill 正文
     * @param prompts        平台前缀、锁定段在业务段之前
     * @param budgets        三类字符预算
     * @param builtinSchemas BUILTIN 的 Java schema；MCP 用库表 schema
     */
    public TrialPromptFactory(AgentToolBindRepository toolBinds, AgentSkillBindRepository skillBinds,
                              ToolItemRepository items, ToolSourceRepository sources, SkillDefRepository skills,
                              SystemPromptAssembler prompts, PromptBudgetProperties budgets,
                              BuiltinSchemaSource builtinSchemas) {
        this.toolBinds = toolBinds;
        this.skillBinds = skillBinds;
        this.items = items;
        this.sources = sources;
        this.skills = skills;
        this.prompts = prompts;
        this.budgets = budgets;
        this.builtinSchemas = builtinSchemas;
    }

    /**
     * 不带用户输入的装配：仅用于配置预览或测试，此时没有任何 Skill 会被硬触发。
     *
     * @param agent 已启用的定义
     * @return 系统提示、可注册内置/MCP 工具、装配说明和注入统计
     */
    public Built build(AgentDef agent) {
        return build(agent, null, null);
    }

    /**
     * 不带记忆段的装配：测试与不需要长期记忆的路径使用。
     *
     * @param agent     已启用的定义
     * @param userQuery 本轮用户原文；命中某 Skill 的 trigger_keywords 即强制注入该 Skill 正文
     * @return 系统提示、可注册内置/MCP 工具、装配说明和注入统计
     */
    public Built build(AgentDef agent, String userQuery) {
        return build(agent, userQuery, null);
    }

    /**
     * 正式装配：组装顺序为「L1 与锁定段 → 开放段与 L3 → 记忆 → Skill → 工具目录」。
     *
     * @param agent     已启用的定义
     * @param userQuery 本轮用户原文；命中某 Skill 的 trigger_keywords 即强制注入该 Skill 正文
     * @param memory    记忆段正文（【工作记忆】/【用户偏好】，由 {@code TrialMemoryCoordinator} 取得）；
     *                  可空。它必须在这里进链，不能在装配完成后往提示词尾部拼——
     *                  那样记忆会落在工具目录之后，模型先读工具清单再读偏好
     * @return 系统提示、结构描述、可注册内置/MCP 工具、装配说明和注入统计
     */
    public Built build(AgentDef agent, String userQuery, String memory) {
        List<String> notices = new ArrayList<>();
        List<PromptInjectionComposer.SkillInput> skillInputs = skillsOf(agent.getId(), userQuery);
        PreparedTools prepared = toolsOf(agent.getId(), notices);
        SystemPromptAssembler.Assembled assembled = prompts.assemble(agent, userQuery);
        PromptInjectionComposer.Outcome outcome = PromptInjectionComposer.compose(
                new PromptInjectionComposer.Assembled(assembled.text(), assembled.layout()),
                memory,
                skillInputs,
                prepared.inputs(),
                new PromptInjectionComposer.Budgets(
                        budgets.systemBudget(), budgets.skillsBudget(), budgets.toolsSchemaBudget(),
                        budgets.forcedSkillBudget(),
                        budgets.onDemandSkillBody()
                                ? PromptInjectionComposer.OnDemandBodyMode.FIT_BUDGET
                                : PromptInjectionComposer.OnDemandBodyMode.HEADER_ONLY));
        // 通知按段区分：其它段截断 ≠ 工具额度耗尽，避免「仍有工具额度」时误报未暴露
        if (outcome.stats().isPromptSegmentTruncated()) {
            notices.add("提示词已按预算截断");
        }
        // 强制 Skill 被硬上限裁过：模型执行的是残缺规则，必须让使用者看见
        if (outcome.stats().getForcedSkillsClipped() > 0) {
            notices.add("强制 Skill 正文超过平台上限已截断：" + outcome.stats().getForcedSkillsClipped() + " 条");
        }
        List<BuiltinToolSpec> builtins = new ArrayList<>();
        List<McpToolSpec> mcps = new ArrayList<>();
        Set<String> schemaNames = new HashSet<>(outcome.schemaNames());
        if (outcome.stats().isToolSchemaBudgetHit()) {
            for (String omitted : outcome.omittedCallableNames()) {
                if (omitted != null && !omitted.isBlank()) {
                    notices.add("工具 " + omitted + " 因完整 schema 超出预算未对本轮暴露");
                }
            }
        }
        for (PromptInjectionComposer.ToolInput tool : prepared.inputs()) {
            // 只注册预算内拿到完整 schema 的可调用工具，与提示词目录一致
            if (!tool.callable() || !schemaNames.contains(tool.name())) {
                continue;
            }
            McpPending mcp = prepared.mcpByName().get(tool.name());
            if (mcp != null) {
                McpToolSpec spec = new McpToolSpec();
                spec.setName(tool.name());
                spec.setDescription(tool.oneLiner());
                spec.setInputSchemaJson(mcp.schema());
                spec.setSourceId(mcp.sourceId());
                mcps.add(spec);
            } else {
                BuiltinToolSpec spec = new BuiltinToolSpec();
                spec.setName(tool.name());
                builtins.add(spec);
            }
        }
        PromptLayout layout = assembled.layout() == null ? PromptLayout.EMPTY : assembled.layout();
        return new Built(outcome.prompt(), layout, builtins, mcps, notices, outcome.stats());
    }

    /**
     * 收集本 Agent 已绑定、绑定开、Skill 自身启用的 Skill。
     *
     * <p>两种进法：
     * <ul>
     *   <li>常规：进目录，永远带 title + description（场景），供模型按需判断；
     *       description 为空时退回 name，绝不把 remark 当场景。</li>
     *   <li>强制：{@code trigger_keywords} 任一非空词出现在本轮用户输入里，
     *       直接标记 triggered=true，由 Composer 拼接固定前缀 + 正文，不再等模型决定。</li>
     * </ul>
     * 停用或绑定关闭的 Skill 在这里就被剔除，因此既不会进目录也不会硬触发。
     *
     * @param agentId   Agent
     * @param userQuery 本轮用户原文，可空；为空时不会有任何硬触发
     * @return 按绑定序的 Skill 注入输入
     */
    private List<PromptInjectionComposer.SkillInput> skillsOf(Long agentId, String userQuery) {
        List<PromptInjectionComposer.SkillInput> rows = new ArrayList<>();
        for (AgentSkillBind bind : skillBinds.listByAgentId(agentId)) {
            // 绑定关即不注入；与工具同一判据，避免「绑定关了正文仍进提示词」
            if (!ToolEnablement.bindingOn(bind.getStatus())) {
                continue;
            }
            // Skill 停用同样不得注入全文，也不参与强制触发
            Optional<SkillDef> skill = skills.findById(bind.getSkillId())
                    .filter(item -> ToolEnablement.itemOn(item.getStatus()));
            if (skill.isEmpty()) {
                continue;
            }
            SkillDef one = skill.get();
            String title = one.getTitle() == null || one.getTitle().isBlank() ? one.getName() : one.getTitle();
            // 场景取 description（简介），供模型判断何时加载；remark 是内部备注，永不注入
            String scene = one.getDescription() == null ? "" : one.getDescription();
            boolean triggered = SkillTriggerMatcher.matches(userQuery, one.getTriggerKeywords());
            rows.add(new PromptInjectionComposer.SkillInput(title, scene, one.getContentMd(), triggered));
        }
        return rows;
    }

    /**
     * 按绑定 id 升序收集可调用工具；MCP / BUILTIN 都进同一列表，供预算按序记账。
     */
    private PreparedTools toolsOf(Long agentId, List<String> notices) {
        List<PromptInjectionComposer.ToolInput> rows = new ArrayList<>();
        Map<String, McpPending> mcpByName = new HashMap<>();
        Set<String> names = new HashSet<>();
        for (AgentToolBind bind : toolBinds.listByAgentId(agentId)) {
            // 绑定关：整条排除，不进目录也不进 schema
            if (!ToolEnablement.bindingOn(bind.getStatus())) {
                continue;
            }
            // 工具项不在白名单（MCP 新同步默认 0）：不得进 schema
            Optional<ToolItem> item = items.findById(bind.getToolItemId())
                    .filter(one -> ToolEnablement.itemOn(one.getStatus()));
            if (item.isEmpty()) {
                notices.add("跳过未启用的工具绑定");
                continue;
            }
            // 源停用等价整源下线：不得进 schema，即使绑定与工具项都开着
            Optional<ToolSource> source = sources.findById(item.get().getSourceId())
                    .filter(one -> ToolEnablement.sourceOn(one.getStatus()));
            if (source.isEmpty()) {
                notices.add("跳过工具 " + item.get().getName() + "：工具源不可用");
                continue;
            }
            String name = item.get().getName() == null ? "" : item.get().getName().trim();
            if (name.isEmpty() || !names.add(name)) {
                continue;
            }
            String oneLiner = item.get().getTitle() == null || item.get().getTitle().isBlank()
                    ? blank(item.get().getDescription())
                    : item.get().getTitle().trim();
            boolean mcp = ToolSourceType.MCP.name().equalsIgnoreCase(source.get().getType());
            boolean builtin = ToolSourceType.BUILTIN.name().equalsIgnoreCase(source.get().getType());
            if (!mcp && !builtin) {
                notices.add("跳过工具 " + name + "：未知源类型");
                continue;
            }
            String schema;
            if (builtin) {
                // BUILTIN：Java 注解优先，库表只是退路
                schema = BuiltinSchemaSource.prefer(item.get().getInputSchemaJson(),
                        builtinSchemas.jsonFor(name).orElse(null));
            } else {
                // MCP：同步进库的 input_schema_json；空则 Composer 按占位成本暴露并仍可注册
                schema = item.get().getInputSchemaJson();
                mcpByName.put(name, new McpPending(source.get().getId(), schema));
            }
            rows.add(new PromptInjectionComposer.ToolInput(name, oneLiner, schema, true));
        }
        return new PreparedTools(rows, mcpByName);
    }

    private static String blank(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /**
     * @param inputs    按绑定序的可调用工具
     * @param mcpByName MCP 名 → 源与库表 schema；内置不在此 map
     */
    private record PreparedTools(List<PromptInjectionComposer.ToolInput> inputs, Map<String, McpPending> mcpByName) {
    }

    /**
     * @param sourceId 工具源
     * @param schema   库表 schema，可空
     */
    private record McpPending(Long sourceId, String schema) {
    }

    /**
     * @param prompt   系统提示（顺序：L1 与锁定段 → 开放段与 L3 → 记忆 → Skill → 工具目录）
     * @param layout   结构描述：受保护前缀长度与各段正文，供出站校验与预算裁剪使用
     * @param tools    需要注册的内置工具
     * @param mcpTools 需要注册可调用句柄的 MCP 工具
     * @param notices  装配说明
     * @param stats    注入统计
     */
    public record Built(String prompt, PromptLayout layout, List<BuiltinToolSpec> tools, List<McpToolSpec> mcpTools,
                        List<String> notices, PromptInjectionStats stats) {
    }
}
