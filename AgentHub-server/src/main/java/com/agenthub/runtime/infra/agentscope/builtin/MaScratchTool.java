package com.agenthub.runtime.infra.agentscope.builtin;

import com.agenthub.runtime.domain.model.ScratchBoard;
import com.agenthub.runtime.domain.service.LimitHitReasons;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.infra.agentscope.BuiltinToolSchemas;
import com.agenthub.runtime.infra.agentscope.JavaSchemaAgentTool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排共享草稿区的两个平台工具（{@code ma_scratch_get} / {@code ma_scratch_put}）。所属域：runtime 的 infra。
 *
 * <p>为什么是「每轮实例」而不是 Spring 单例：草稿板本身是 run 级状态，
 * 若做成单例 Bean 就只能靠 ThreadLocal 在工具线程里取板（工具跑在 boundedElastic 上，
 * 需要额外的 Toolkit 包装才能把板带过去）。这里让工具实例直接持有本轮那块板：
 * 注册进 Toolkit 的闭包天然跟随本轮，线程切换不影响正确性，也不会串轮。
 *
 * <p>注入条件：只有编排根选中 {@code maContextPolicy=SHARED_SCRATCH} 时才调用 {@link #install}。
 * 未选中时 Toolkit 里<b>没有</b>这两个名字，模型也看不到目录，回归不受影响。
 *
 * <p>工具纪律：
 * <ul>
 *   <li>{@code ma_scratch_get}：只读，key 不存在返回 {@code found=false}，不报错；</li>
 *   <li>{@code ma_scratch_put}：同键只允许首次写入，第二次返回结构化错误（{@code SCRATCH_KEY_CONFLICT}）；</li>
 *   <li>返回值一律紧凑 JSON，便于模型解析；不把 value 打进日志。</li>
 * </ul>
 */
public class MaScratchTool {

    /** 读取工具调用名；交付口径写死，改名视为契约变更 */
    public static final String TOOL_GET = "ma_scratch_get";
    /** 写入工具调用名；交付口径写死，改名视为契约变更 */
    public static final String TOOL_PUT = "ma_scratch_put";

    /** 两个工具名，按注册顺序 */
    public static final List<String> TOOL_NAMES = List.of(TOOL_GET, TOOL_PUT);

    /** 追加到系统提示末尾的段（声明可调用工具 + 写入纪律）；固定短文本，不占预算计算 */
    private static final String PROMPT_SECTION = """

            ## 共享草稿区（本轮编排）
            - ma_scratch_get：按 key 读取本轮编排已写入的草稿；key 不存在时返回 found=false，不报错。
            - ma_scratch_put：按 key 写入草稿；同一个 key 只允许首次写入，第二次写入会被拒绝（原值不变）。
            草稿仅本轮编排内共享、随本轮结束丢弃；需要让其它子看到结论时，请主动写入终答正文或本草稿区。""";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 本轮草稿板，多个子共享同一实例 */
    private final ScratchBoard board;
    /** 本子写入归属：route_key，缺省时用子 Agent 名 */
    private final String ownerRouteKey;

    /**
     * @param board         本轮共享草稿板，非空
     * @param ownerRouteKey 写入归属（route_key / 子名），可空
     */
    public MaScratchTool(ScratchBoard board, String ownerRouteKey) {
        this.board = board;
        this.ownerRouteKey = ownerRouteKey == null ? "" : ownerRouteKey;
    }

    /**
     * 把两个工具注册进本轮 Toolkit，并用 Java 注解生成的 schema 覆盖引擎扫描结果。
     *
     * <p>调用方：{@code ReActTrialExecutor} 在装配完绑定工具之后、交给引擎之前调用。
     * 重复注册会被跳过，因此 P&E 每步重装提示词也不会出现重名工具。
     *
     * @param toolkit 已装配工具集
     * @param board   本轮草稿板；null 或 toolkit 为 null 时原样返回
     * @param ownerRouteKey 写入归属
     * @return 同一 toolkit
     * @throws TrialFailure 注册后引擎仍看不到工具名（说明 AgentScope 版本行为变了）
     */
    public static Toolkit install(Toolkit toolkit, ScratchBoard board, String ownerRouteKey) {
        if (toolkit == null || board == null) {
            return toolkit;
        }
        MaScratchTool tool = new MaScratchTool(board, ownerRouteKey);
        // 已经在（P&E 多步复用同一 Toolkit 的极端情况）就不再注册，避免重名覆盖
        if (toolkit.getTool(TOOL_GET) != null && toolkit.getTool(TOOL_PUT) != null) {
            return toolkit;
        }
        try {
            toolkit.registerTool(tool);
        } catch (RuntimeException ex) {
            throw new TrialFailure("共享草稿工具注册失败", LimitHitReasons.ERROR, ex);
        }
        for (String name : TOOL_NAMES) {
            if (toolkit.getTool(name) == null) {
                throw new TrialFailure("共享草稿工具「" + name + "」注册后不可调用");
            }
            overlaySchema(toolkit, tool, name);
        }
        return toolkit;
    }

    /**
     * 读取一个草稿键。所属域：runtime。
     *
     * @param key 草稿键（字母开头，≤64 字符）
     * @return 紧凑 JSON：found / key / value / ownerRouteKey / message；不存在时 found=false
     */
    @Tool(
            name = TOOL_GET,
            description = "读取本轮编排共享草稿区里的一个键。仅本轮内可见；key 不存在时返回 found=false，不报错。"
                    + "用于拿到其它子 Agent 已经写入的中间结论。",
            readOnly = true,
            concurrencySafe = true)
    public String scratchGet(
            @ToolParam(name = "key", description = "草稿键：字母开头，后续只允许字母、数字、下划线、点、斜杠、连字符，最长 64 字符") String key) {
        ScratchBoard.ReadResult result = board.get(key);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("found", result.found());
        body.put("key", result.key());
        if (result.found()) {
            body.put("value", result.value());
            body.put("ownerRouteKey", result.ownerRouteKey());
        }
        body.put("message", result.message());
        return json(body);
    }

    /**
     * 写入一个草稿键。所属域：runtime。
     *
     * <p>冲突不杀子：同键第二次写入只返回失败结果，子可以改读或换 key 继续跑；
     * 整子失败仍由子自身 ReAct / P&E 的失败语义决定。
     *
     * @param key   草稿键
     * @param value 要共享的文本（≤4000 字符；超长失败，不静默截断）
     * @return 紧凑 JSON：ok / key / code / message；冲突时附首写者 ownerRouteKey
     */
    @Tool(
            name = TOOL_PUT,
            description = "向本轮编排共享草稿区写入一个键值。同一个 key 只允许首次写入：再次写入会被拒绝且原值不变。"
                    + "适合把中间结论共享给其它子 Agent；value 最长 4000 字符。",
            readOnly = false,
            concurrencySafe = false)
    public String scratchPut(
            @ToolParam(name = "key", description = "草稿键：字母开头，后续只允许字母、数字、下划线、点、斜杠、连字符，最长 64 字符") String key,
            @ToolParam(name = "value", description = "要共享的文本，最长 4000 字符；超长会失败而不是截断") String value) {
        ScratchBoard.WriteResult result = board.put(key, value, ownerRouteKey);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", result.ok());
        body.put("key", result.key());
        body.put("code", result.code());
        body.put("message", result.message());
        String owner = result.ownerRouteKey();
        if (owner != null && !owner.isBlank()) {
            body.put("ownerRouteKey", owner);
        }
        return json(body);
    }

    /**
     * @return 追加到系统提示末尾的共享草稿段（工具名 + 写入纪律）；不调工具时提示词不变
     */
    public static String promptSection() {
        return PROMPT_SECTION;
    }

    /**
     * 用 Java 注解生成的 parameters 覆盖引擎扫描结果，
     * 与 {@code BuiltinToolkitAssembler} 的 overlay 同口径（引擎默认不扫长度上限与 required）。
     */
    private static void overlaySchema(Toolkit toolkit, Object bean, String name) {
        Map<String, Object> parameters;
        try {
            parameters = BuiltinToolSchemas.parametersOf(bean, name);
        } catch (RuntimeException ex) {
            return;
        }
        if (parameters == null) {
            return;
        }
        AgentTool current = toolkit.getTool(name);
        if (current == null || current instanceof JavaSchemaAgentTool) {
            return;
        }
        toolkit.registerAgentTool(new JavaSchemaAgentTool(current, parameters));
    }

    /**
     * @param body 结果字段
     * @return 紧凑 JSON；序列化失败兜底成可读文本（工具结果不应把整子打掉）
     */
    private static String json(Map<String, Object> body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            return "{\"ok\":false,\"code\":\"SCRATCH_INTERNAL\",\"message\":\"草稿工具结果序列化失败\"}";
        }
    }
}
