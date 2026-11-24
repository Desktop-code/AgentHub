package com.agenthub.runtime.application;

import com.agenthub.runtime.application.router.NestedRoute;
import com.agenthub.runtime.domain.model.AgentChoice;
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.GeneratedFile;
import com.agenthub.runtime.domain.model.ParticipantView;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.model.TrialTrace;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.service.GeneratedFileMarker;
import com.agenthub.runtime.domain.service.ParticipantJson;
import com.agenthub.runtime.domain.service.TrialTexts;
import com.agenthub.runtime.domain.service.WorkbenchChoiceJson;
import com.agenthub.runtime.domain.service.WorkflowRunJson;
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
        /*
         * 入口块：先把「这一步开始」与判定推理落库，再写判定结果。
         * 顺序不能反：前端回放时，首条 route 之前的推理才算入口的思考；
         * 判定句一旦先落，后面的思考就会被算到第一位参与者名下。
         */
        // 入口身份在判定推理之前：回放靠它认入口，不把这段思考算到第一位子专家
        seq = appendParticipant(sessionId, seq, routed.gateway(), written);
        if (routed.judgeReasoning() != null && !routed.judgeReasoning().isBlank()) {
            written.add(writer.append(sessionId, seq++, EventType.REASONING, routed.judgeReasoning(), null, null));
        }
        if (routed.decisionBlock() != null && !routed.decisionBlock().isBlank()) {
            written.add(writer.append(sessionId, seq++, EventType.ROUTE, routed.decisionBlock(), null, null));
        }
        List<RouterTrialExecutor.RouteSegment> segments = routed.segments();
        if (segments == null || segments.isEmpty()) {
            if (routed.summary() != null && !routed.summary().isBlank()) {
                written.add(writer.append(sessionId, seq++, EventType.ROUTE, routed.summary(), null, null));
            }
            // 没有分段时整轮只有这一份终答：过程与文件写在这里，正文留给调用方写在结论标记之后
            seq = appendTraces(sessionId, seq, routed.turn(), written);
            seq = appendFiles(sessionId, seq, routed.turn(), written);
            return appendConclusion(sessionId, seq, routed, written);
        }
        for (RouterTrialExecutor.RouteSegment segment : segments) {
            // 身份行紧挨在该参与者的路由 / 思考之前，与直播 begin 同一顺序
            seq = appendParticipant(sessionId, seq, segment.participant(), written);
            if (segment.routeLine() != null && !segment.routeLine().isBlank()) {
                written.add(writer.append(sessionId, seq++, EventType.ROUTE, segment.routeLine(), null, null));
            }
            /*
             * 方案分流判定推理：必须紧跟在「路由到 方案」之后、各子专家的「路由到」之前。
             * 这样回放时它才会落在方案块里，而不是被算到某位子专家名下。
             */
            if (segment.reasoning() != null && !segment.reasoning().isBlank()) {
                written.add(writer.append(sessionId, seq++, EventType.REASONING, segment.reasoning(), null, null));
            }
            if (segment.error() != null && !segment.error().isBlank()) {
                written.add(writer.append(sessionId, seq++, EventType.SYSTEM,
                        "子 Agent 失败：" + segment.error(), null, null));
            }
            /*
             * 工作流执行卡片：紧跟本段「路由到」之后落库。
             * 不落这条的话，刷新后只剩一段正文——时间轴与产物区整块消失。
             * 卡片是状态快照不是增量，正文用 JSON，与 SSE 帧同形（前端一套解析）。
             */
            if (segment.workflow() != null) {
                // 落库只存骨架：节点产出在 workflow_node_run，刷新回放按 runId 现算
                String payload = WorkflowRunJson.compact(segment.workflow());
                if (!payload.isEmpty()) {
                    written.add(writer.append(sessionId, seq++, EventType.WORKFLOW,
                            WorkflowRunJson.summary(segment.workflow()), payload, null, null));
                }
            }
            /*
             * 目标方案的终答 turn 已把各子的 traces 并进来（AnswerAggregator#mergeTurns）：
             * 这种情况不能再整体写一次，否则既重复，又会让子专家的思考全部挂在方案名下。
             * 改为逐跳写：方案之后紧跟「路由到 子专家」+ 该子自己的 traces。
             */
            if (!segment.children().isEmpty()) {
                // 父段 turn 是各子终稿的拼接或汇总，不能再整段写在父卡上
                seq = appendNested(sessionId, seq, segment.children(), written);
                /*
                 * 目标方案自己的汇总结论：必须紧跟它分下去的各子专家之后落库。
                 *
                 * <p>为什么不能只推实时帧：目标方案是「另一套编排方案」，它自己的汇总不走外层
                 * {@link #appendConclusion}（那是入口那一层的），漏了这一条，回放时前端拿不到
                 * 「方案结论：X」标记，汇总记录就画不出来——用户看到的是「综合台结论出过又消失」。
                 *
                 * <p>外层结论同名时让位：单意图直跑同一个方案时，外层写的就是这一份，写两遍会出两条。
                 */
                seq = appendNestedConclusion(sessionId, seq, segment, routed, written);
            } else if (segment.turn() != null) {
                // 这一位自己的终稿写在他的思考 / 工具之后、下一位 begin 之前
                seq = appendParticipantTurn(sessionId, seq, segment.turn(), written);
            }
        }
        return appendConclusion(sessionId, seq, routed, written);
    }

    /**
     * 目标方案（另一套编排方案）内部那次真汇总：标记行 + 汇总模型推理。
     *
     * <p>三条口径：
     * <ul>
     *   <li>没有真汇总（CONCAT / 降级）时 `conclusionName` 为空 → 一行都不写，绝不假造汇总块；</li>
     *   <li>外层结论<strong>同名</strong>（单意图直跑同一个方案）→ 让位，同一份汇总不写两遍；</li>
     *   <li>外层结论是另一个名字，或外层根本不汇总（多问各自成文）→ 照写标记、推理和汇总正文。
     *       正文不写的话，回放只剩外层拼接稿，汇总记录会被看成空的。</li>
     * </ul>
     *
     * @param sessionId 会话主键
     * @param seq       当前序号
     * @param segment   方案段（路由行 + 内部各跳 + 它自己的汇总结论）
     * @param routed    本轮结果（同名时让位，避免写两条同名的结论）
     * @param written   已写事件收集器
     * @return 推进后的下一条序号
     */
    private int appendNestedConclusion(Long sessionId, int seq, RouterTrialExecutor.RouteSegment segment,
                                       RouterTrialExecutor.RoutedTurn routed, List<RunEvent> written) {
        String name = segment.conclusionName();
        if (name == null || name.isBlank()) {
            return seq;
        }
        // 同名即同一份汇总：外层 appendConclusion 会写它，这里不重复
        String outer = routed.conclusionName();
        if (outer != null && outer.trim().equalsIgnoreCase(name.trim())) {
            return seq;
        }
        seq = appendParticipant(sessionId, seq, conclusionOf(name), written);
        written.add(writer.append(sessionId, seq++, EventType.ROUTE, "方案结论：" + name, null, null));
        if (segment.conclusionReasoning() != null && !segment.conclusionReasoning().isBlank()) {
            written.add(writer.append(sessionId, seq++, EventType.REASONING,
                    segment.conclusionReasoning(), null, null));
        }
        /*
         * 汇总正文必须紧跟标记。直播是在「方案结论：」之后把这段字推出去的；
         * 落库若只留标记和推理，回放就只剩外层拼接稿。投影会把它当成「正文已发回各卡」，
         * 汇总记录上就只剩那句占位。这里写下的是方案终答里的同一段字，不另造一条卡。
         */
        String summary = segment.turn() == null ? null : segment.turn().getAssistantText();
        if (summary != null && !summary.isBlank()) {
            written.add(writer.append(sessionId, seq++, EventType.ASSISTANT, summary, null, null));
        }
        return seq;
    }

    /**
     * 真汇总结论落库：先「方案结论：X」标记，再汇总模型自己的思考。
     *
     * <p>为什么标记也要落库：前端靠它把汇总另起一条记录（排在所有子专家之后）。
     * 以前只推了实时 SSE，刷新后标记消失，末尾的汇总结论就被兜底认成最后一位子专家的正文。
     *
     * @param sessionId 会话主键
     * @param seq       当前序号
     * @param routed    本轮结果
     * @param written   已写事件收集器
     * @return 推进后的下一条序号
     */
    private int appendConclusion(Long sessionId, int seq, RouterTrialExecutor.RoutedTurn routed,
                                 List<RunEvent> written) {
        if (routed.conclusionName() == null || routed.conclusionName().isBlank()) {
            return seq;
        }
        seq = appendParticipant(sessionId, seq, conclusionOf(routed.conclusionName()), written);
        written.add(writer.append(sessionId, seq++, EventType.ROUTE,
                "方案结论：" + routed.conclusionName(), null, null));
        if (routed.conclusionReasoning() != null && !routed.conclusionReasoning().isBlank()) {
            written.add(writer.append(sessionId, seq++, EventType.REASONING,
                    routed.conclusionReasoning(), null, null));
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
            seq = appendParticipant(sessionId, seq, child.participant(), written);
            if (child.routeLine() != null && !child.routeLine().isBlank()) {
                written.add(writer.append(sessionId, seq++, EventType.ROUTE, child.routeLine(), null, null));
            }
            if (child.error() != null && !child.error().isBlank()) {
                written.add(writer.append(sessionId, seq++, EventType.SYSTEM,
                        "子 Agent 失败：" + child.error(), null, null));
            }
            if (child.turn() != null) {
                // 子专家的终稿只属于这一跳，不并进方案卡，也不等整轮末尾那条总稿
                seq = appendParticipantTurn(sessionId, seq, child.turn(), written);
            }
        }
        return seq;
    }

    /**
     * 落一行参与者开始事件。正文是 {@link ParticipantJson}，思考和终答仍是纯文本。
     *
     * @param sessionId 会话主键
     * @param seq       当前序号
     * @param who       身份；空则不写
     * @param written   已写事件
     * @return 推进后的序号
     */
    private int appendParticipant(Long sessionId, int seq, ParticipantView who, List<RunEvent> written) {
        if (who == null || who.id() == null || who.id().isBlank()) {
            return seq;
        }
        String payload = ParticipantJson.payload(who, true);
        if (payload == null) {
            return seq;
        }
        written.add(writer.append(sessionId, seq++, EventType.PARTICIPANT, who.name(), payload, null, null));
        return seq;
    }

    /**
     * 单独补写一行 begin（入口卡 / 方案卡）。
     *
     * <p>只为停等补写开放：嵌套方案的入口与方案卡在对外的正常路径里由各自的包装器推，
     * 停等时整轮还没落库，只能在落等待态前手动补齐，否则刷新后成员卡没有父。
     * 与 {@link #appendParticipant} 同一份 payload 口径，不新增事件类型。
     *
     * @param sessionId 会话主键
     * @param seq       当前序号
     * @param who       身份；空则原样返回 seq
     * @param written   已写事件
     * @return 推进后的序号
     */
    int appendParticipantRow(Long sessionId, int seq, ParticipantView who, List<RunEvent> written) {
        return appendParticipant(sessionId, seq, who, written);
    }

    /**
     * 真汇总的身份。直播不靠 conclusion-begin 建卡，回放仍先落这一行，再写「方案结论：」。
     *
     * @param name 方案名
     * @return 汇总身份
     */
    private static ParticipantView conclusionOf(String name) {
        String title = name == null ? "" : name.trim();
        return ParticipantView.conclusion("c-" + title, title);
    }

    /**
     * 这一位参与者的过程与终稿：思考、工具、终稿、文件按直播终态的顺序落在同一段里。
     *
     * <p>终稿必须写在下一位 begin 之前。整轮末尾那条总稿会跟着最后一位走，
     * 不能代替这里的逐人落库。
     *
     * @param sessionId 会话主键
     * @param seq       下一条序号
     * @param turn      这一位自己的执行结果
     * @param written   已写事件
     * @return 推进后的下一条序号
     */
    int appendParticipantTurn(Long sessionId, int seq, TrialTurn turn, List<RunEvent> written) {
        if (turn == null) {
            return seq;
        }
        seq = appendTraces(sessionId, seq, turn, written);
        seq = appendOwnAssistant(sessionId, seq, turn, written);
        return appendFiles(sessionId, seq, turn, written);
    }

    /**
     * 把这一位已经生成完的终稿写成助手正文。
     *
     * <p>空正文、待批准计划不写：空事件会让回放多出一只空框，
     * 计划轮本来就没有终答。有正文才落，和直播结果框是同一段字。
     *
     * @param sessionId 会话主键
     * @param seq       下一条序号
     * @param turn      这一位的执行结果
     * @param written   已写事件
     * @return 推进后的下一条序号
     */
    private int appendOwnAssistant(Long sessionId, int seq, TrialTurn turn, List<RunEvent> written) {
        if (turn == null || turn.isAssistantSuppressed()) {
            return seq;
        }
        String text = turn.getAssistantText();
        if (text == null || text.isBlank()) {
            return seq;
        }
        written.add(assistant(sessionId, seq, turn));
        return seq + 1;
    }

    /**
     * 分段已经各自落下终稿之后，整轮还要不要再写一条助手正文。
     *
     * <p>没有分段时，终答只在这一条上，必须写。有分段且本轮真的做了汇总时，
     * 汇总正文不在任何子段里，要写在「方案结论」之后。其余情况子段已经各有终稿，
     * 再写拼接稿只会贴到最后一位参与者身上。
     *
     * @param routed 本轮路由结果
     * @return true 表示调用方还应写 {@code routed.turn()} 的助手正文
     */
    static boolean owesRoundAssistant(RouterTrialExecutor.RoutedTurn routed) {
        if (routed == null || routed.turn() == null) {
            return false;
        }
        String summary = routed.turn().getAssistantText();
        if (summary == null || summary.isBlank() || routed.turn().isAssistantSuppressed()) {
            return false;
        }
        if (routed.conclusionName() != null && !routed.conclusionName().isBlank()) {
            return true;
        }
        List<RouterTrialExecutor.RouteSegment> segments = routed.segments();
        return segments == null || segments.isEmpty();
    }

    /**
     * 按引擎顺序写思考和工具。空白思考跳过，避免没有中间步时造出假 reasoning。
     * 工具行带脱敏参数。结果另起一段放正文。没有参数时不补空 JSON。
     *
     * <p>这里不写终稿、不写文件：终稿要跟在工具后面、又要排在文件前面，
     * 由 {@link #appendParticipantTurn} 串起来。没有分段的合并轮只走本方法，
     * 终稿仍由调用方写一次。
     */
    int appendTraces(Long sessionId, int seq, TrialTurn turn, List<RunEvent> written) {
        if (turn == null || turn.getTraces() == null) {
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
        return seq;
    }

    /**
     * 把本轮生成的文件落成 FILE 事件。
     *
     * <p>为什么必须落库：产物卡片靠 SSE 实时出现，但事件一旦不落库，
     * 刷新页面或换设备后这张卡片就消失了——用户在历史会话里找不到自己刚生成的文件。
     * 正文用结构化 JSON，与 SSE 帧、内存快照共用同一字段形状，前端一套解析即可。
     *
     * <p>去重口径与前端一致：同一参与者写同名文件只保留最后一次生成的那条；
     * 不同参与者各写同名文件是两份产物，各落一条（否则文件会改挂到别人名下）。
     * 正文带 {@code participantId / participantName}，刷新回放按落库身份挂卡，
     * 不再靠「最后一位参与者」猜作者。
     *
     * @param sessionId 会话主键
     * @param seq       下一条事件序号
     * @param turn      本轮结果
     * @param written   本轮已写事件
     * @return 推进后的下一条序号
     */
    int appendFiles(Long sessionId, int seq, TrialTurn turn, List<RunEvent> written) {
        if (turn == null || turn.getFiles() == null || turn.getFiles().isEmpty()) {
            return seq;
        }
        for (GeneratedFile file : turn.getFiles()) {
            if (file == null || file.fileName() == null || file.fileName().isBlank()) {
                continue;
            }
            String summary = file.fileName() + "（" + file.humanSize() + "）";
            written.add(writer.append(sessionId, seq++, EventType.FILE, summary,
                    GeneratedFileMarker.payload(file.fileName(), file.size(),
                            file.participantId(), file.participantName()), null, null));
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
        return assistant(sessionId, seq, turn, true);
    }

    /**
     * 助手答复事件。
     *
     * <p>{@code recordTokens=false} 用于「各子段已经各自记过用量」之后补上的汇总正文，
     * 避免评测把同一轮的 token 再加一次。
     *
     * @param sessionId    会话主键
     * @param seq          事件序号
     * @param turn         本轮或这一位的结果
     * @param recordTokens 是否把这份结果上的 token 写进事件
     * @return 落库后的事件
     */
    RunEvent assistant(Long sessionId, int seq, TrialTurn turn, boolean recordTokens) {
        // 摘要给列表；正文走 content_text，保留 Markdown 换行，不在这里折行
        Integer prompt = recordTokens ? turn.getPromptTokens() : null;
        Integer completion = recordTokens ? turn.getCompletionTokens() : null;
        return writer.append(sessionId, seq, EventType.ASSISTANT, turn.getAssistantText(), prompt, completion);
    }

    /**
     * 结构化选项事件：摘要是问题句，正文是 {@code {"question","options"}} 的 JSON。
     *
     * <p>为什么必须落库而不是只推 SSE：刷新后要按同一份结构化数据重建 chips，
     * 断点续作也要用它校验 optionId；正文里不再有机器块，所以它不能靠助手正文回放。
     *
     * @param sessionId 会话主键
     * @param seq       事件序号（紧跟本轮 ASSISTANT 之后）
     * @param choice    已校验的选项
     * @return 落库后的事件；结构不可用时返回 null（调用方不推进 seq）
     */
    RunEvent choice(Long sessionId, int seq, AgentChoice choice) {
        String payload = WorkbenchChoiceJson.write(choice);
        if (payload.isEmpty()) {
            return null;
        }
        String question = choice.question() == null ? "" : choice.question().trim();
        String summary = question.isEmpty() ? "需要你选择" : question;
        return writer.append(sessionId, seq, EventType.CHOICE, summary, payload, null, null);
    }
}
