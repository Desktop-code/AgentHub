package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.model.GeneratedFile;
import com.agenthub.runtime.domain.service.GeneratedFileMarker;
import com.agenthub.runtime.domain.service.TrialProgress;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 拦截工具结果里的「文件已生成」标记。所属域：runtime 的 infra。
 *
 * <p><b>为什么必须在工具层拦，而不是只在 SSE 层清洗：</b>
 * AgentScope 把工具返回值原样拼进下一轮 messages 回灌给模型。
 * 若只在出站 SSE 里正则剔字，模型上下文里仍会留着
 * {@code [FILE_GENERATED]{"fileName":...}[/FILE_GENERATED]} 这串机械字符，
 * 模型会把它当成待解释的文本复述出来，反而更脏。故在这里做两件事：
 * <ol>
 *   <li>提取产物 → 交给 {@link Consumer} 推给前端（出站 {@link TrialProgress#file}）；</li>
 *   <li>把标记从正文里<b>物理剔除</b>后返回给引擎，模型只看到人话。</li>
 * </ol>
 *
 * <p>套用顺序（见 {@code ReActTrialExecutor}）：配额 → 必填闸门 → 本类 → RAG 上下文。
 * 必须包在配额与闸门<b>外层</b>，否则被闸门拦下的调用（没有真正执行、也不会有标记）
 * 仍会白跑一次解析；而套在最外层能保证「谁产生的标记都能被看见」，包括 MCP 工具。
 *
 * <p>线程：{@code ToolExecutor} 在 {@code boundedElastic} 上跑工具，
 * 标记处理是纯 CPU 局部运算，不依赖任何 ThreadLocal；产物回调由构造方保证线程安全
 * （出站写入器自身加锁，内存快照写入器也加锁）。
 */
public final class FileMarkerAgentTool implements AgentTool {

    private final AgentTool delegate;
    private final Consumer<GeneratedFile> sink;

    /**
     * @param delegate 原工具
     * @param sink     产物出口，可空；空则只剔除标记、不推送
     */
    public FileMarkerAgentTool(AgentTool delegate, Consumer<GeneratedFile> sink) {
        this.delegate = delegate;
        this.sink = sink;
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
     * 调原工具，拿到结果后提取产物并把标记剔掉。
     *
     * @param param 引擎入参
     * @return 已清洗的结果；没有标记时原样返回同一个对象，避免无谓重建
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return delegate.callAsync(param).map(this::scrub);
    }

    /**
     * 清洗一条工具结果。
     *
     * <p>只处理 SUCCESS 与未设状态的块：失败结果里的标记不该被当成产物
     * （工具失败时不会写标记，但 MCP 等外部实现的返回值不可控，这里做防御）。
     *
     * @param result 原结果
     * @return 清洗后的结果；无标记或不该处理时返回原对象
     */
    private ToolResultBlock scrub(ToolResultBlock result) {
        if (result == null) {
            return null;
        }
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
