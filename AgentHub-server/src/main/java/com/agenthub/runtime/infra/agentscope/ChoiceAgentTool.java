package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.model.AgentChoice;
import com.agenthub.runtime.domain.model.ChoiceCapture;
import com.agenthub.runtime.domain.service.CauseDigest;
import com.agenthub.runtime.domain.service.InternalToolNames;
import com.agenthub.runtime.domain.service.LimitHitReasons;
import com.agenthub.runtime.domain.service.TrialFailure;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「需要用户拍板」的结构化产出工具（{@code agenthub_choice}）。所属域：runtime 的 infra。
 *
 * <p>为什么用工具而不是让模型输出机器块：自然语言里夹一段围栏 JSON 的做法完全依赖模型自觉，
 * 漏一次用户就没有可点选项；工具调用是模型协议的一部分，参数有 JSON Schema 约束，
 * 平台能直接拿到结构化结果，不必再从正文里猜。工具返回一句固定话术，
 * 引导模型在调用之后用一句话收尾，而不是再把选项罗列一遍。
 *
 * <p>工具纪律：
 * <ul>
 *   <li>只注册给「工作台单专家 / 入口直分叶子 / 断点续作」这一轮，其它入口看不到它；</li>
 *   <li>参数非法（少于 2 项、超过 6 项、id 重复、缺 label）时返回错误结果让模型自己修正，
 *       不落任何选项；</li>
 *   <li>调用过程对用户不可见：{@link InternalToolNames#isQuiet(String)} 命中后，
 *       时间线不记 TOOL 事件、前端不出现「正在调用」胶囊，正文里也不会露出 JSON。</li>
 * </ul>
 *
 * <p>线程安全：每次安装都是新实例，只持有本轮那个 {@link ChoiceCapture} 引用；
 * 工具跑在 boundedElastic 线程上也能写回同一个对象。
 */
public final class ChoiceAgentTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(ChoiceAgentTool.class);

    /** 工具调用名；交付口径写死，改名视为契约变更 */
    public static final String TOOL = InternalToolNames.CHOICE;

    /** 模型调用成功后的回执：只给一句行为指引，不回灌选项内容（避免正文复述一遍） */
    private static final String ACK = "{\"ok\":true,\"message\":\"选项已展示给用户。请用一句话收尾，"
            + "不要重复罗列这些选项，也不要输出 JSON。\"}";

    private static final Map<String, Object> PARAMETERS = schema();

    /** 本轮采集槽，工具结果写回这里，由请求线程统一落库 */
    private final ChoiceCapture capture;

    /**
     * @param capture 本轮采集槽，非空
     */
    private ChoiceAgentTool(ChoiceCapture capture) {
        this.capture = capture;
    }

    /**
     * 把工具注册进本轮 Toolkit，并标记本轮允许拍板。
     *
     * <p>调用方：{@code ReActTrialExecutor} 在装配完绑定工具之后、包装配额与闸门之前调用。
     *
     * @param toolkit 已装配工具集
     * @param capture 本轮采集槽；null 或 toolkit 为 null 时原样返回且不标记
     * @return 同一 toolkit
     * @throws TrialFailure 注册后引擎仍看不到工具名（说明 AgentScope 版本行为变了）
     */
    public static Toolkit install(Toolkit toolkit, ChoiceCapture capture) {
        if (toolkit == null || capture == null) {
            return toolkit;
        }
        capture.markEligible();
        // P&E 多步复用同一 Toolkit 的极端情况：已经注册过就再挂一个
        if (toolkit.getTool(TOOL) != null) {
            return toolkit;
        }
        try {
            toolkit.registerAgentTool(new ChoiceAgentTool(capture));
        } catch (RuntimeException ex) {
            // 注册失败会让整轮起不来：只有这里带全栈，才能看出是版本反射还是 schema 问题
            log.warn("注册选项工具失败 type={} cause={}", ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            throw new TrialFailure("选项工具注册失败", LimitHitReasons.ERROR, ex);
        }
        if (toolkit.getTool(TOOL) == null) {
            throw new TrialFailure("选项工具「" + TOOL + "」注册后不可调用");
        }
        return toolkit;
    }

    /** {@inheritDoc} */
    @Override
    public String getName() {
        return TOOL;
    }

    /** {@inheritDoc} */
    @Override
    public String getDescription() {
        return "需要用户在若干方案中拍板时调用：提交一句话问题与 2~6 个选项（id + label）。"
                + "调用后前端会把选项渲染成可点按钮，用户点击即继续执行；"
                + "不要在正文里重复罗列同一组选项，也不要输出 JSON。";
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> getParameters() {
        return PARAMETERS;
    }

    /** {@inheritDoc} 与内置工具同口径：参数必须严格符合 schema */
    @Override
    public Boolean getStrict() {
        return Boolean.TRUE;
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> getOutputSchema() {
        return null;
    }

    /** {@inheritDoc} 只收进本轮上下文，不产生副作用 */
    @Override
    public boolean isReadOnly() {
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isReturnDirect() {
        return false;
    }

    /**
     * 收下一份选项。参数不合法时返回错误结果（模型可见，可自己修正），不写入采集槽。
     *
     * @param param 引擎入参：question + options[{id,label}]
     * @return 成功回执或错误说明
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            Map<String, Object> input = param == null ? null : param.getInput();
            AgentChoice choice = read(input);
            if (choice == null) {
                return ToolResultBlock.error(TOOL,
                        "选项结构不合法：需要 question（一句话问题）与 options（2~6 项，每项含非空 id 与 label，id 不重复）。"
                                + "请修正后重新调用。");
            }
            capture.accept(choice);
            return new ToolResultBlock(TOOL, TOOL,
                    List.of(TextBlock.builder().text(ACK).build()), null, ToolResultState.SUCCESS);
        });
    }

    /**
     * 把工具入参映射成选项结构并做统一校验。
     *
     * @param input 引擎给的对象参数，可空
     * @return 合法结构；任何一项不合格返回 null
     */
    private static AgentChoice read(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        String question = asText(input.get("question"));
        Object rawOptions = input.get("options");
        if (!(rawOptions instanceof List<?> list)) {
            return null;
        }
        List<AgentChoice.Option> options = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> row)) {
                return null;
            }
            options.add(new AgentChoice.Option(asText(row.get("id")), asText(row.get("label"))));
        }
        // 与后端整理、旧机器块共用同一套边界
        return AgentChoice.of(question, options);
    }

    /**
     * @param value 参数值，可空
     * @return 去首尾空白后的字符串；非字符串类型按 toString 收下（部分模型会回数字 id）
     */
    private static String asText(Object value) {
        if (value == null) {
            return "";
        }
        return value instanceof String text ? text.trim() : value.toString().trim();
    }

    /**
     * 工具参数 schema。手写而不是从 Java 注解生成：选项是嵌套对象数组，
     * {@link BuiltinToolSchemas} 只支持平铺标量参数，用注解会退化成 string。
     *
     * @return 不可变 schema
     */
    private static Map<String, Object> schema() {
        Map<String, Object> optionProperties = new LinkedHashMap<>();
        optionProperties.put("id", property("string", "同一份选项内唯一的短标识（如 vue3 / react / a1）"));
        optionProperties.put("label", property("string", "给用户看的选项文案，一句话，不要写理由"));
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("type", "object");
        option.put("properties", optionProperties);
        option.put("required", List.of("id", "label"));

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("type", "array");
        options.put("description", "2~6 个候选选项，顺序即展示顺序");
        options.put("minItems", AgentChoice.MIN_OPTIONS);
        options.put("maxItems", AgentChoice.MAX_OPTIONS);
        options.put("items", option);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("question", property("string", "一句话问题：让用户选什么"));
        properties.put("options", options);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", properties);
        root.put("required", List.of("question", "options"));
        return Collections.unmodifiableMap(root);
    }

    private static Map<String, Object> property(String type, String description) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", type);
        node.put("description", description);
        return node;
    }
}
