package com.agenthub.runtime.application;

import com.agenthub.runtime.application.router.NestedRoute;
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.GeneratedFile;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.model.TrialTrace;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.service.GeneratedFileMarker;
import com.agenthub.runtime.domain.service.TrialTexts;
import com.agenthub.runtime.infra.agentscope.ToolResultClipper;

import java.util.List;

/**
 * 把「一轮执行结果」摊平成时间线事件。所属域：runtime 的 application。
 *
 * <p>职责边界：只管**事件怎么写**（顺序、类型、摘要/正文的截断与脱敏、同名文件去重），
 * 不决定「这一轮该怎么执行」，也不碰会话状态、事务与观测。
 * 落库动作全部委托给 {@link TrialEventWriter}（它负责列宽截断与脱敏）。
 *
 * <p>为什么单独成类：{@code TrialRunAppService} 里这一段（计划帧 / Router 分段 / 思考与工具 /
 * 文件 / 助手答复）只依赖 {@code writer} 与几个纯函数，与执行骨架无关；抽出来后 AppService
 * 只保留「调用顺序」，改时间线形状不必再在千行类里定位。
 *
 * <p>线程安全：无状态（只持有不可变依赖）；每次调用自带 {@code sessionId} 与 {@code seq}，
 * 同一会话的并发写入由上层 {@code TrialFlight} 串行化，本类不做加锁。
 */
final class TrialTimelineWriter {

    /** 事件落库入口（列宽截断、脱敏都在它里面）。 */
    private final TrialEventWriter writer;

    /**
     * @param writer 事件落库入口
     */
    TrialTimelineWriter(TrialEventWriter writer) {
        this.writer = writer;
    }

    /**
     * 把收集到的时间线帧写成事件。PLAN / STEP 带结构化 JSON 正文，前端按 planId / seq 还原结构；
     * REASONING / TOOL 是计划阶段与各步内部的思考与工具，按发生顺序落在对应位置。
     *
     * @param frames 按发生顺序的帧
     * @return 推进后的下一条序号
     */
    int appendPlanFrames(Long sessionId, int seq, List<PlanProgressRecorder.Frame> frames,
                         List<RunEvent> written) {
        if (frames == null) {
            return seq;
        }
        for (PlanProgressRecorder.Frame frame : frames) {
            written.add(writer.append(sessionId, seq++, frame.type(), frame.summary(), frame.content(), null, null));
        }
        return seq;
    }

    /**
     * Router 思考按子分段落库：可选决策块 → 每子一条 ROUTE → 该子 reasoning/tool。
     * 无 segments 时退回旧的单条 summary + 合并 traces（兼容）。
     *
     * <p>目标方案（MA）内部还有一跳：本段写完后紧接着把方案自己分给各子专家的
     * 「路由到」行与它们的 reasoning/tool 落库，时间线才能按「方案 → 各子专家」逐块展示，
     * 而不是只留一句「子编排内部：路由到…」。
     */
    int appendRouterSegments(Long sessionId, int seq, RouterTrialExecutor.RoutedTurn routed,
                             List<RunEvent> written) {
        if (routed.decisionBlock() != null && !routed.decisionBlock().isBlank()) {
            written.add(writer.append(sessionId, seq++, EventType.ROUTE, routed.decisionBlock(), null, null));
        }
        List<RouterTrialExecutor.RouteSegment> segments = routed.segments();
        if (segments == null || segments.isEmpty()) {
            if (routed.summary() != null && !routed.summary().isBlank()) {
                written.add(writer.append(sessionId, seq++, EventType.ROUTE, routed.summary(), null, null));
            }
            return appendTraces(sessionId, seq, routed.turn(), written);
        }
        for (RouterTrialExecutor.RouteSegment segment : segments) {
            if (segment.routeLine() != null && !segment.routeLine().isBlank()) {
                written.add(writer.append(sessionId, seq++, EventType.ROUTE, segment.routeLine(), null, null));
            }
            if (segment.error() != null && !segment.error().isBlank()) {
                written.add(writer.append(sessionId, seq++, EventType.SYSTEM,
                        "子 Agent 失败：" + segment.error(), null, null));
            }
            /*
             * 目标方案的终答 turn 已把各子的 traces 并进来（AnswerAggregator#mergeTurns）：
             * 这种情况不能再整体写一次，否则既重复，又会让子专家的思考全部挂在方案名下。
             * 改为逐跳写：方案之后紧跟「路由到 子专家」+ 该子自己的 traces。
             */
            if (!segment.children().isEmpty()) {
                seq = appendNested(sessionId, seq, segment.children(), written);
            } else if (segment.turn() != null) {
                seq = appendTraces(sessionId, seq, segment.turn(), written);
            }
        }
        return seq;
    }

    /**
     * 写目标方案内部的各跳。层级最深只有一层（装载时已剔除目标方案自己的 MA 候选），
     * 因此只需顺序写 routeLine + traces，失败同样落一条中文 SYSTEM。
     *
     * @param sessionId 会话主键
     * @param seq       当前序号
     * @param children  方案内部各跳；可空
     * @param written   已写事件收集器
     * @return 推进后的下一条序号
     */
    private int appendNested(Long sessionId, int seq, List<NestedRoute> children, List<RunEvent> written) {
        if (children == null || children.isEmpty()) {
            return seq;
        }
        for (NestedRoute child : children) {
            if (child == null) {
                continue;
            }
            if (child.routeLine() != null && !child.routeLine().isBlank()) {
                written.add(writer.append(sessionId, seq++, EventType.ROUTE, child.routeLine(), null, null));
            }
            if (child.error() != null && !child.error().isBlank()) {
                written.add(writer.append(sessionId, seq++, EventType.SYSTEM,
                        "子 Agent 失败：" + child.error(), null, null));
            }
            if (child.turn() != null) {
                seq = appendTraces(sessionId, seq, child.turn(), written);
            }
        }
        return seq;
    }

    /**
     * 按引擎顺序写思考和工具。空白思考跳过，避免没有中间步时造出假 reasoning。
     * 工具行带脱敏参数。结果另起一段放正文。没有参数时不补空 JSON。
     */
    int appendTraces(Long sessionId, int seq, TrialTurn turn, List<RunEvent> written) {
        if (turn.getTraces() == null) {
            return seq;
        }
        for (TrialTrace trace : turn.getTraces()) {
            if (trace == null || trace.getType() == null) {
                continue;
            }
            if (trace.getType() == EventType.REASONING) {
                if (trace.getContent() == null || trace.getContent().isBlank()) {
                    continue;
                }
                written.add(writer.append(sessionId, seq++, EventType.REASONING, trace.getContent(), null, null));
                continue;
            }
            if (trace.getType() == EventType.TOOL) {
                String line = TrialTexts.toolDisplay(trace.getName(), trace.getArguments());
                if (!trace.isSuccess()) {
                    line = line + " · 失败";
                }
                // 时间线正文与回灌同截并可见截断标记；obs TOOL span 仍用 traces 脱敏全文
                String result = ToolResultClipper.clipText(trace.getContent(), turn.getToolResultMaxChars());
                String body = result == null || result.isBlank() ? line : line + "\n\n" + result;
                written.add(writer.append(sessionId, seq++, EventType.TOOL, line, body, null, null));
            }
        }
        seq = appendFiles(sessionId, seq, turn, written);
        return seq;
    }

    /**
     * 把本轮生成的文件落成 FILE 事件。
     *
     * <p>为什么必须落库：产物卡片靠 SSE 实时出现，但事件一旦不落库，
     * 刷新页面或换设备后这张卡片就消失了——用户在历史会话里找不到自己刚生成的文件。
     * 正文用结构化 JSON，与 SSE 帧、内存快照共用同一字段形状，前端一套解析即可。
     *
     * <p>去重口径与前端一致：同名文件只保留最后一次生成的那条，
     * 避免同一轮反复写同一路径时时间线上刷出一排重复卡片。
     *
     * @param sessionId 会话主键
     * @param seq       下一条事件序号
     * @param turn      本轮结果
     * @param written   本轮已写事件
     * @return 推进后的下一条序号
     */
    int appendFiles(Long sessionId, int seq, TrialTurn turn, List<RunEvent> written) {
        if (turn.getFiles() == null || turn.getFiles().isEmpty()) {
            return seq;
        }
        for (GeneratedFile file : turn.getFiles()) {
            if (file == null || file.fileName() == null || file.fileName().isBlank()) {
                continue;
            }
            String summary = file.fileName() + "（" + file.humanSize() + "）";
            written.add(writer.append(sessionId, seq++, EventType.FILE, summary,
                    GeneratedFileMarker.payload(file.fileName(), file.size()), null, null));
        }
        return seq;
    }

    /**
     * 助手答复事件：摘要进 summary，正文走 content_text（保留 Markdown 换行）。
     *
     * @param sessionId 会话主键
     * @param seq       事件序号
     * @param turn      本轮结果
     * @return 落库后的事件
     */
    RunEvent assistant(Long sessionId, int seq, TrialTurn turn) {
        // 摘要给列表；正文走 content_text，保留 Markdown 换行，不在这里折行
        return writer.append(sessionId, seq, EventType.ASSISTANT, turn.getAssistantText(),
                turn.getPromptTokens(), turn.getCompletionTokens());
    }
}
