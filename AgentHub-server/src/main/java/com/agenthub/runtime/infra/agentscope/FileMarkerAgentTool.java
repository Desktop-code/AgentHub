package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.model.GeneratedFile;
import com.agenthub.runtime.domain.model.PromptInjectionStats;
import com.agenthub.runtime.domain.service.GeneratedFileMarker;
import com.agenthub.runtime.domain.service.ToolResultHandle;
import com.agenthub.runtime.domain.service.TrialProgress;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 工具结果的出口加工：剔除产物标记 + 超长结果句柄化。所属域：runtime 的 infra。
 *
 * <p><b>为什么必须在这一层做，而不是只在 SSE 层清洗或在出站时截断：</b>
 * AgentScope 把工具返回值原样拼进下一轮 messages 回灌给模型。两件事都必须在
 * 「结果刚生成、上下文还没被污染」的时候完成：
 * <ol>
 *   <li><b>产物标记</b>：{@code [FILE_GENERATED]{"fileName":...}[/FILE_GENERATED]} 这串机械字符
 *       若留在上下文里，模型会把它当待解释的文本复述出来，反而更脏。这里提取后<b>物理剔除</b>；</li>
 *   <li><b>超长结果</b>：几万字的网页正文/日志若整段回灌，后续每一轮都要重新计费。
 *       超过阈值就落到会话工作区，只回灌「摘要 + 虚拟路径」，模型要看细节再按行读。
 *       落盘的前提是<b>当前专家真的绑定了读文件工具</b>：没有就保持原样交给回灌截断，
 *       绝不写成「请调用某个未绑定的工具」——那会把专家逼进反复重搜→自我熔断。</li>
 * </ol>
 *
 * <p>套用顺序（见 {@code ReActTrialExecutor}）：配额 → 必填闸门 → 本类 → RAG 上下文。
 * 必须包在配额与闸门<b>外层</b>，否则被闸门拦下的调用（没有真正执行、也不会有标记）
 * 仍会白跑一次解析；而套在最外层能保证「谁产生的标记都能被看见」，包括 MCP 工具。
 *
 * <p>两类加工的顺序有讲究：<b>先去标记，再判断是否需要句柄化</b>。
 * 标记本身有几十个字符，先剔掉再量长度，才不会因为一串机械字符把一条本该原样的结果送去落盘。
 *
 * <p>线程：{@code ToolExecutor} 在 {@code boundedElastic} 上跑工具，
 * 标记处理是纯 CPU 局部运算，不依赖任何 ThreadLocal；产物回调与落盘由构造方保证线程安全
 * （出站写入器自身加锁，{@link ToolResultOffloadStore} 无状态且文件名按纳秒唯一）。
 */
public final class FileMarkerAgentTool implements AgentTool {

    private final AgentTool delegate;
    private final Consumer<GeneratedFile> sink;
    /** 超长结果落盘；可空表示不启用句柄化（老测试与不需要该能力的链路） */
    private final ToolResultOffloadStore offload;
    /** 落盘阈值（字符）；≤0 关闭 */
    private final int offloadMaxChars;
    /**
     * 本轮注入统计；可空。
     * <p>
     * 为什么直接持有而不是从 ThreadLocal 取：工具在 {@code boundedElastic} 上执行，
     * ThreadLocal 不会跨线程传播，取到的必然是 null。装配时就把统计传进来，才记得到账。
     */
    private final PromptInjectionStats stats;
    /**
     * 当前专家已绑定的读文件工具名；空串表示「没有可回读的工具」。
     * <p>
     * 它决定超长结果要不要落盘：没有读文件工具时落盘只会留下一个读不到的路径，
     * 还会把模型逼去调用不存在的工具（检索号专家自我熔断的根因就在这）。
     */
    private final String readToolName;

    /**
     * 只做标记剔除（不启用句柄化）。
     *
     * @param delegate 原工具
     * @param sink     产物出口，可空；空则只剔除标记、不推送
     */
    public FileMarkerAgentTool(AgentTool delegate, Consumer<GeneratedFile> sink) {
        this(delegate, sink, null, 0, null, null);
    }

    /**
     * 兼容构造：不声明读文件工具（老测试与不需要该能力的链路）。
     *
     * @param delegate        原工具
     * @param sink            产物出口，可空
     * @param offload         落盘器，可空；空则不启用句柄化
     * @param offloadMaxChars 落盘阈值（字符）；≤0 关闭
     * @param stats           本轮注入统计，可空
     */
    public FileMarkerAgentTool(AgentTool delegate, Consumer<GeneratedFile> sink,
                               ToolResultOffloadStore offload, int offloadMaxChars,
                               PromptInjectionStats stats) {
        this(delegate, sink, offload, offloadMaxChars, stats, null);
    }

    /**
     * @param delegate        原工具
     * @param sink            产物出口，可空
     * @param offload         落盘器，可空；空则不启用句柄化
     * @param offloadMaxChars 落盘阈值（字符）；≤0 关闭
     * @param stats           本轮注入统计，可空；非空时记录落盘条数与原始字符数
     * @param readToolName    当前专家已绑定的读文件工具名；空表示不落盘、只在结果里留可读正文
     */
    public FileMarkerAgentTool(AgentTool delegate, Consumer<GeneratedFile> sink,
                               ToolResultOffloadStore offload, int offloadMaxChars,
                               PromptInjectionStats stats, String readToolName) {
        this.delegate = delegate;
        this.sink = sink;
        this.offload = offload;
        this.offloadMaxChars = offloadMaxChars;
        this.stats = stats;
        this.readToolName = readToolName == null ? "" : readToolName.trim();
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

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> getParameters() {
        return delegate.getParameters();
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
     * 调原工具，拿到结果后先剔标记、再按阈值决定是否落盘。
     *
     * @param param 引擎入参（含 {@code RuntimeContext}，落盘要用它的 sessionId）
     * @return 加工后的结果；两件事都不需要时原样返回同一个对象，避免无谓重建
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String sessionId = param == null || param.getRuntimeContext() == null
                ? null : param.getRuntimeContext().getSessionId();
        // 工具名只能从入参取：工具返回值的 id/name 在引擎里是 null（它们只作为消息 ContentBlock 时才有）
        String toolName = toolNameOf(param);
        return delegate.callAsync(param).map(result -> process(result, sessionId, toolName));
    }

    /**
     * 从引擎入参取工具调用名。
     *
     * <p>为什么必须这样取：{@code ToolResultBlock} 作为「工具方法返回值」时 id 与 name 都是 null，
     * 只在作为消息块时才被填上。若直接用 {@code result.getName()}，落盘文件名会退化成
     * {@code tool-xxx.txt}，多个工具的产物混在一个目录里分不清是谁的。
     *
     * @param param 引擎入参，可空
     * @return 工具名；取不到时返回 null（落盘侧会退化成 tool）
     */
    private String toolNameOf(ToolCallParam param) {
        if (param == null) {
            return null;
        }
        if (param.getToolUseBlock() != null && param.getToolUseBlock().getName() != null) {
            return param.getToolUseBlock().getName();
        }
        try {
            return delegate.getName();
        } catch (RuntimeException ex) {
            // 委托对象取名字失败不该影响结果加工
            return null;
        }
    }

    /**
     * 单条结果的完整加工：剔除产物标记 → 判断并落盘超长正文。
     *
     * @param result    原结果，可空
     * @param sessionId 会话标识，可空（空时无法落盘，只做标记剔除）
     * @param toolName  工具名，可空（用于落盘文件名与回灌摘要）
     * @return 加工后的结果；无需改动时返回原对象
     */
    private ToolResultBlock process(ToolResultBlock result, String sessionId, String toolName) {
        if (result == null) {
            return null;
        }
        ToolResultBlock scrubbed = scrub(result);
        return handleOversize(scrubbed, sessionId, toolName);
    }

    /**
     * 剔除产物标记。
     *
     * <p>只处理 SUCCESS 与未设状态的块：失败结果里的标记不该被当成产物
     * （工具失败时不会写标记，但 MCP 等外部实现的返回值不可控，这里做防御）。
     *
     * @param result 原结果
     * @return 清洗后的结果；无标记或不该处理时返回原对象
     */
    private ToolResultBlock scrub(ToolResultBlock result) {
        List<ContentBlock> output = result.getOutput();
        if (output == null || output.isEmpty()) {
            return result;
        }
        boolean changed = false;
        List<ContentBlock> next = new ArrayList<>(output.size());
        for (ContentBlock block : output) {
            if (!(block instanceof TextBlock textBlock)) {
                next.add(block);
                continue;
            }
            String text = textBlock.getText();
            GeneratedFileMarker.Scan scan = GeneratedFileMarker.scan(text);
            if (scan.files().isEmpty() && scan.clean().equals(text)) {
                next.add(block);
                continue;
            }
            // 先推送再改写：产物信息只存在于标记里，剔除后就再也拿不到了
            publish(scan.files());
            String clean = scan.clean();
            // 整段只剩空白的极端情况（工具只返回了一个标记）：给一句可读说明，
            // 不能让模型收到空工具结果——那会被引擎当成「工具没返回内容」而反复重试
            next.add(TextBlock.builder()
                    .text(clean.isBlank() ? "（该工具已结束，无文本输出）" : clean)
                    .build());
            changed = true;
        }
        if (!changed) {
            return result;
        }
        return new ToolResultBlock(result.getId(), result.getName(), next, result.getMetadata(), result.getState());
    }

    /**
     * 超长正文落盘，用「摘要 + 路径」替换原正文。
     * <p>
     * 只看第一段文本块：一条工具结果里出现多个文本块是极少数情况，
     * 逐块落盘会产生一堆小文件，反而更难让模型找。超长时整条结果合成一份文件。
     * <p>
     * <b>没有读文件工具就不落盘</b>：那会把结果换成一个读不到的路径，还会诱导模型
     * 去调不存在的工具（检索专家反复重搜直到 consecutive_fail_abort 就是这么来的）。
     * 此时保持原样，交给回灌截断吐出一段可读头部正文，当前专家能直接据它作答。
     *
     * @param result    已剔除标记的结果
     * @param sessionId 会话标识，可空
     * @param toolName  工具名，可空
     * @return 替换后的结果；不需要或落盘失败时返回原对象
     */
    private ToolResultBlock handleOversize(ToolResultBlock result, String sessionId, String toolName) {
        if (offload == null || offloadMaxChars <= 0) {
            return result;
        }
        if (readToolName.isEmpty()) {
            // 当前专家没有读文件工具：不落盘、不点名，正文留给回灌截断（可读的头部）
            return result;
        }
        List<ContentBlock> output = result.getOutput();
        if (output == null || output.isEmpty()) {
            return result;
        }
        // 只跳过明确的失败：ToolResultBlock 会把未设状态的块默认成 RUNNING，
        // 而工具方法直接返回时通常就是 RUNNING（引擎之后才改成 SUCCESS）。
        // 若把「非 SUCCESS」当失败，正常的成功结果就永远落不了盘。
        if (result.getState() == ToolResultState.ERROR) {
            return result;
        }
        String text = firstText(output);
        if (text == null) {
            return result;
        }
        ToolResultHandle handle = offload
                .offload(sessionId, toolName, text, true, offloadMaxChars, readToolName)
                .orElse(null);
        if (handle == null || !handle.stored()) {
            return result;
        }
        // 记账：本轮省了多少字是验收口径，必须能从上车的统计里看到
        meterOffload(handle.totalChars());
        // 用单块摘要替换全部文本块：原正文已在文件里，继续留着等于没落盘
        List<ContentBlock> replaced = new ArrayList<>(output.size());
        boolean placed = false;
        for (ContentBlock block : output) {
            if (block instanceof TextBlock && !placed) {
                replaced.add(TextBlock.builder().text(handle.preview()).build());
                placed = true;
            } else if (!(block instanceof TextBlock)) {
                replaced.add(block);
            }
        }
        return new ToolResultBlock(result.getId(), result.getName(), replaced,
                result.getMetadata(), result.getState());
    }
    /**
     * 记一条落盘到本轮注入统计。
     * <p>
     * 统计对象在构造时注入；为空时静默跳过（统计是旁路信息，不该影响工具结果）。
     * 并发安全由 {@code PromptInjectionStats} 的简单自增承担：这里只加计数，
     * 偶发的竞态最多让条数少算一条，不会影响结论，也不值得为它加锁拖慢工具路径。
     *
     * @param rawChars 被落盘的原始正文长度
     */
    private void meterOffload(int rawChars) {
        if (stats == null) {
            return;
        }
        stats.meterOffloadedToolResult(rawChars);
    }

    /**
     * @param output 结果块
     * @return 第一个非空文本块的正文；没有则 null
     */
    private static String firstText(List<ContentBlock> output) {
        for (ContentBlock block : output) {
            if (block instanceof TextBlock textBlock) {
                String text = textBlock.getText();
                if (text != null && !text.isEmpty()) {
                    return text;
                }
            }
        }
        return null;
    }

    /**
     * 逐个推给前端。单个回调失败不影响工具结果回灌：产物卡片是锦上添花，
     * 不能让一次推送异常把整轮工具调用打断。
     *
     * @param files 本次提取到的产物
     */
    private void publish(List<GeneratedFile> files) {
        if (sink == null || files.isEmpty()) {
            return;
        }
        for (GeneratedFile file : files) {
            try {
                sink.accept(file);
            } catch (RuntimeException ex) {
                // 出站已断等情况：忽略即可，工具结果仍要正常回给模型
            }
        }
    }
}
