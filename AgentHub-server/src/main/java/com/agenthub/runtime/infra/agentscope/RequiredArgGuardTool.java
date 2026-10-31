package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.service.RequiredToolArgs;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 必填参数闸门：套在已注册工具外侧，缺必填或疑似编造时直接拦住，不触达真实工具。所属域：runtime 的 infra。
 *
 * <p>这是硬拦：校验不通过时只把追问文案作为工具结果回给模型，绝不调用 {@code delegate}，
 * 因此不会产生任何出站请求（MCP {@code tools/call}、内置 HTTP 等都不会发生）。
 * 校验通过且由长期记忆补参时，用补全后的入参调用原工具。
 *
 * <p>必须套在 {@link BudgetingAgentTool} 之外（配额之后）：
 * 被拦下的调用没有真正执行，不计入工具配额，也不计入连续失败次数，避免模型重试时把整轮跑崩。
 * {@link RagContextAgentTool} 可再套在本类之外，只负责换线程重绑 Agent 上下文。
 */
public final class RequiredArgGuardTool implements AgentTool {

    private final AgentTool delegate;
    private final RequiredToolArgs.Evidence evidence;
    private final RequiredToolArgs.Rules rules;
    private final Function<String, Optional<String>> canonicalOf;
    private final RequiredArgBlocks blocks;
    /** 原工具 schema 的 required；本类自行强制，模型侧 schema 不再据此预驳回 */
    private final List<String> required;
    /** 给模型看的 schema：去掉了 required，避免引擎抢先回「请改正参数重试」诱导模型编造 */
    private final Map<String, Object> exposedParameters;

    /**
     * @param delegate    原工具，负责真正执行
     * @param evidence    本轮可用的事实来源（当前用户话、会话上下文、已注入长期记忆）
     * @param rules       实体参数判据
     * @param canonicalOf 参数名/事实键 → 规范事实键
     * @param blocks      本轮拦截计数，可空
     */
    public RequiredArgGuardTool(AgentTool delegate, RequiredToolArgs.Evidence evidence,
                                RequiredToolArgs.Rules rules, Function<String, Optional<String>> canonicalOf,
                                RequiredArgBlocks blocks) {
        this.delegate = delegate;
        this.evidence = evidence == null ? RequiredToolArgs.Evidence.empty() : evidence;
        this.rules = rules == null ? RequiredToolArgs.Rules.defaults() : rules;
        this.canonicalOf = canonicalOf;
        this.blocks = blocks;
        this.required = RequiredToolArgs.requiredOf(delegate.getParameters());
        this.exposedParameters = relaxRequired(delegate.getParameters());
    }

    /** {@inheritDoc} */
    @Override
    public String getName() {
        return delegate.getName();
    }

    /** {@inheritDoc} */
    @Override
    public String getDescription() {
        return delegate.getDescription();
    }

    /**
     * 必填校验全部由本类完成：模型侧 schema 去掉 required 后，缺参调用会进到 {@link #callAsync}，
     * 由我们回「向用户追问」，而不是被引擎的通用驳回文案推着模型换值重试。
     * properties 与类型约束保持原样，不影响模型理解参数含义。
     *
     * @return 去掉了 required 的 parameters 副本
     */
    @Override
    public Map<String, Object> getParameters() {
        return exposedParameters;
    }

    /** {@inheritDoc} */
    @Override
    public Boolean getStrict() {
        return delegate.getStrict();
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> getOutputSchema() {
        return delegate.getOutputSchema();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isReadOnly() {
        return delegate.isReadOnly();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isReturnDirect() {
        return delegate.isReturnDirect();
    }

    /**
     * 先做必填完备性检查；不通过则只回追问文案，通过则按（可能已补全的）入参调用原工具。
     *
     * @param param 引擎入参
     * @return 原工具结果，或被拦下时的错误结果
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param == null ? Map.of() : param.getInput();
        RequiredToolArgs.Result checked = RequiredToolArgs.check(
                getName(), required, input, evidence, rules, canonicalOf);
        if (!checked.allowed()) {
            // 硬拦：没有出站，也没有消耗工具配额；把追问要求明确写回给模型
            int blocked = blocks == null ? 1 : blocks.record(getName());
            return Mono.just(rejected(param, escalate(checked.message(), blocked)));
        }
        // 无补参或拿不到原调用参数（引擎不会这样调）：原样透传，不改动引擎给的入参
        if (checked.filled().isEmpty() || param == null) {
            return delegate.callAsync(param);
        }
        // 由长期记忆补全：沿用原调用标识，只换入参
        ToolCallParam filled = ToolCallParam.builder(param).input(checked.input()).build();
        return delegate.callAsync(filled);
    }

    /**
     * 复制 schema 并去掉 required，同时在对应属性的 description 前补「（必填）」。
     * 这样模型仍知道哪些参数必填，但缺参时不会被引擎的通用驳回拦住，
     * 而是进到本类拿到「向用户追问」的明确指引，不会被推着换值重试。
     *
     * @param parameters 原工具 parameters，可空
     * @return 可安全交给模型的副本
     */
    private static Map<String, Object> relaxRequired(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>(parameters);
        Object rawRequired = copy.remove("required");
        if (!(rawRequired instanceof Collection<?> requiredNames) || requiredNames.isEmpty()) {
            return Collections.unmodifiableMap(copy);
        }
        Object rawProperties = copy.get("properties");
        if (!(rawProperties instanceof Map<?, ?> properties) || properties.isEmpty()) {
            return Collections.unmodifiableMap(copy);
        }
        Map<String, Object> marked = new LinkedHashMap<>();
        properties.forEach((key, value) -> marked.put(String.valueOf(key), value));
        for (Object name : requiredNames) {
            if (name == null) {
                continue;
            }
            Object property = marked.get(String.valueOf(name));
            if (property instanceof Map<?, ?> original) {
                Map<String, Object> field = new LinkedHashMap<>();
                original.forEach((k, v) -> field.put(String.valueOf(k), v));
                String description = field.get("description") == null ? "" : field.get("description").toString();
                if (!description.contains("必填")) {
                    field.put("description", description.isBlank() ? "必填参数" : "（必填）" + description);
                }
                marked.put(String.valueOf(name), Collections.unmodifiableMap(field));
            } else {
                // 没写 properties 的必填参数补一个最小占位，保证模型知道它是字符串必填
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("type", "string");
                field.put("description", "必填参数");
                marked.put(String.valueOf(name), Collections.unmodifiableMap(field));
            }
        }
        copy.put("properties", Collections.unmodifiableMap(marked));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * 同一工具被反复拦时加强措辞：第一次给完整原因，之后只保留「必须问用户」这一句，
     * 防止模型把追问提示当成新的参数线索继续试。
     *
     * @param message 首次的完整追问文案
     * @param blocked 本轮该工具累计被拦次数
     * @return 给模型的正文
     */
    private String escalate(String message, int blocked) {
        if (blocked <= 1) {
            return message;
        }
        return "该工具本轮已连续 " + blocked + " 次因必填参数无依据被拦下，仍未执行。"
                + "不要再调用任何工具，直接用中文向用户追问缺少的信息。";
    }

    /**
     * 组装被拦结果。带上调用 id/name，时间线才能和模型那次 tool_call 对上。
     *
     * @param param   原调用
     * @param message 追问文案
     * @return ERROR 态结果，正文即给模型和用户的说明
     */
    private ToolResultBlock rejected(ToolCallParam param, String message) {
        ToolUseBlock use = param == null ? null : param.getToolUseBlock();
        String id = use == null ? null : use.getId();
        String name = use == null || use.getName() == null ? getName() : use.getName();
        return new ToolResultBlock(id, name,
                List.of(TextBlock.builder().text("[未执行] " + message).build()),
                null, ToolResultState.ERROR);
    }
}
