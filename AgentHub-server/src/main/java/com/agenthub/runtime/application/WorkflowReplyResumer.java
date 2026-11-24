package com.agenthub.runtime.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.GeneratedFile;
import com.agenthub.runtime.domain.model.ParticipantView;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.model.WorkflowRunView;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.domain.service.GeneratedFileMarker;
import com.agenthub.runtime.domain.service.ParticipantJson;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.runtime.domain.service.WorkflowAgentFileScope;
import com.agenthub.runtime.domain.service.WorkflowRunJson;
import com.agenthub.runtime.domain.service.WorkflowCardProjector;
import com.agenthub.workflow.application.WorkflowRunAppService;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.service.WorkflowOutcomeTexts;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 「工作流在对话里停等」的续跑接线。所属域：runtime 的 application。
 *
 * <p>职责：工作台某条会话里存在停在 {@code WAITING_REPLY} 的工作流运行时，
 * 把用户在输入框发出的下一句<b>送回同一条运行</b>，并把这一段投影成与首次发起完全
 * 相同形状的工作流卡（同一个 {@code WorkflowCardProjector}）。
 *
 * <p>为什么放在 runtime：入口判路由、事件落库、SSE / 快照都在这一域；工作流域只负责
 * 「回复写进输入、节点收口、继续调度」，不认识对话时间线。两边通过
 * {@link WorkflowRunAppService#resumeWithReply} 交接，不合并引擎。
 *
 * <p>边界（与工程闸门一致）：
 * <ul>
 *   <li><b>不新建运行</b>：只在已有 run 上续跑，入口不会再把这句话当新问题判一遍；</li>
 *   <li><b>不阻塞主路径</b>：找不到等待中的运行、或运行已被并发收口时返回空，
 *       调用方按现网普通一轮继续（用户这句话不会被吞掉）；</li>
 *   <li><b>幂等</b>：同一句话重复投递时，第一次已把 run 迁出 WAITING_REPLY，
 *       第二次查不到等待中的运行，自然按新问题处理，不会把回复重放两次；</li>
 *   <li><b>不写密钥</b>：只处理用户正文与工作流卡片，日志不带正文。</li>
 * </ul>
 *
 * <p>线程：在请求线程内同步调用（与工作流引擎一致），不另起线程池。
 */
@Component
public class WorkflowReplyResumer {

    private static final Logger log = LoggerFactory.getLogger(WorkflowReplyResumer.class);

    /** 只用于解析卡片正文的静态 mapper：避免每次判断都 new 一个 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WorkflowRunAppService workflowRuns;
    private final TrialEventWriter writer;
    private final RunEventRepository events;
    private final ObjectMapper json;
    /** 操作日志：停止等待中的运行属于用户动作，只在日志里记 run 主键，不记正文 */
    private final OperLogWriter operLogWriter;

    /**
     * @param workflowRuns 工作流域的续跑入口（不新建 run）
     * @param writer       事件落库入口（脱敏 / 截断口径与现网一致）
     * @param events       取会话内下一个序号 / 读历史卡片
     * @param json         全站 ObjectMapper（解析定义 / 卡片正文）
     * @param operLogWriter 操作日志
     */
    public WorkflowReplyResumer(WorkflowRunAppService workflowRuns, TrialEventWriter writer,
                                RunEventRepository events, ObjectMapper json, OperLogWriter operLogWriter) {
        this.workflowRuns = workflowRuns;
        this.writer = writer;
        this.events = events;
        this.json = json;
        this.operLogWriter = operLogWriter;
    }

    /**
     * 一次续跑的结果。
     *
     * @param turn   本轮正文（取卡片自己已流出的那段，与回放同一份）
     * @param events 已落库的事件（调用方并入本轮响应）
     */
    public record Resumed(TrialTurn turn, List<RunEvent> events) {
    }

    /**
     * 本会话是否有停在「等待用户回复」的工作流运行。
     *
     * @param sessionId 工作台会话 id
     * @return true 表示下一句应当送回该运行
     */
    public boolean hasWaitingReply(long sessionId) {
        return workflowRuns.findWaitingReplyRun(sessionId).isPresent();
    }

    /**
     * 停止「等待用户回复」中的运行：按现网中断口径收口，会话本身仍可继续。
     *
     * <p>为什么不能走会话取消的普通分支：那一支会把整条会话写成 CANCELLED 并拒绝再发，
     * 而用户在这里只想停掉挂起的那条运行，之后还要接着聊。等待期间请求线程早已结束
     * （没有 ReAct 在跑），因此必须由这里显式取消 run 并补一条中断闭环，
     * 否则刷新后卡片仍停在「等待你的回复」、库里也没有「已中断」。
     *
     * <p>幂等：没有等待中的运行时不写任何事件；重复点停止时第二次查不到 run，直接返回 false。
     *
     * @param sessionId 工作台会话 id
     * @param userId    会话归属人
     * @return true 表示确实停掉了一条等待中的运行
     */
    public boolean stopWaiting(long sessionId, long userId) {
        Optional<WorkflowRun> found = workflowRuns.findWaitingReplyRun(sessionId);
        if (found.isEmpty()) {
            return false;
        }
        WorkflowRun run = found.get();
        WorkflowDef def = workflowRuns.definitionOf(run).orElse(null);
        String name = displayName(def, run);
        String code = def == null || def.getCode() == null ? "" : def.getCode().trim();
        try {
            // 复用现网取消：终态幂等、节点 cancelOpen、OPEN 待办一并收口、trace 自己结
            workflowRuns.cancel(run.getId());
        } catch (RuntimeException ex) {
            log.warn("停止等待回复的运行失败 runId={} sessionId={} type={}",
                    run.getId(), sessionId, ex.getClass().getSimpleName());
            return false;
        }
        appendCancelledCard(sessionId, run, name, code);
        operLogWriter.write("runtime", "workflow_reply_stop", "workflow_run", String.valueOf(run.getId()),
                "停止等待回复的运行 run=" + run.getId());
        return true;
    }

    /**
     * 上一轮这张卡已经流出的正文（含问句）。
     *
     * <p>续跑要在这段后面接着长，而不是从空白重来：用户刚看过的问句不该在回答后消失。
     * 读失败按空处理——最多少一段前文，不能因为读历史把续跑打断。
     *
     * @param sessionId 会话主键
     * @param code      工作流编码（可为空）
     * @param name      工作流展示名（编码为空时按名字匹配）
     * @return 已流出正文；没有或读失败为空串
     */
    private String previousAnswer(long sessionId, String code, String name) {
        try {
            return events.listBySessionId(sessionId).stream()
                    .filter(row -> row != null && EventType.WORKFLOW.code().equalsIgnoreCase(row.getEventType()))
                    .map(RunEvent::getContentText)
                    .filter(text -> text != null && !text.isBlank())
                    .filter(text -> sameCard(text, name, code))
                    .reduce((first, second) -> second)
                    .map(this::seedOf)
                    // 上一轮的「正在等待你的回复」只属于那一轮：续跑后再带上，正文会和节点状态打架
                    .map(WorkflowReplyResumer::withoutWaitingHint)
                    .orElse("");
        } catch (RuntimeException ex) {
            log.warn("续跑读取上一轮卡片正文失败 sessionId={} type={}",
                    sessionId, ex.getClass().getSimpleName());
            return "";
        }
    }

    /**
     * 去掉上一轮卡片正文末尾的平台等待提示，只留用户已经看到的问句。
     *
     * <p>为什么只按段落删、不改中间文字：等待提示由平台在收口时追加在问句之后
     * （如「工作流「X」正在等待你的回复…」）。续跑后的新卡片会接在这段问句后面继续长，
     * 若把提示一起带上，用户会看到「节点已完成」的同一张卡里仍写着「正在等待你的回复」。
     * 只删以「工作流」开头且包含等待字样的整段，问句本身（可能含用户原话）原样保留。
     *
     * @param answer 上一轮卡片正文；可空
     * @return 去掉等待提示后的正文；全是提示时为空串
     */
    private static String withoutWaitingHint(String answer) {
        if (answer == null || answer.isBlank()) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        for (String paragraph : answer.split("\\n\\s*\\n")) {
            String text = paragraph == null ? "" : paragraph.trim();
            if (text.isEmpty()) {
                continue;
            }
            boolean hint = text.startsWith("工作流") && text.contains("等待你的回复");
            if (hint) {
                continue;
            }
            if (kept.length() > 0) {
                kept.append("\n\n");
            }
            kept.append(text);
        }
        return kept.toString().trim();
    }

    /**
     * 续跑卡片的种子正文：上一轮已流出的正文 + 询问卡片上的问句。
     *
     * <p>为什么要把 {@code choice.question} 并进来：等待卡的问句只存在选项协议里，
     * 节点产出顶层的 {@code question} 往往是询问节点自己的收尾句（如「也可以直接在输入框里写…」）。
     * 续跑后旧卡片会被这一张接管（前端不再另画一张等待卡），只在正文里留上那份收尾句
     * 等于把用户刚看到的问句丢了。问句已经出现在正文里时不重复拼。
     *
     * @param payloadJson 上一轮卡片正文
     * @return 续跑要带上的种子正文；没有可用内容时为空串
     */
    private String seedOf(String payloadJson) {
        String answer = answerOf(payloadJson);
        String question = choiceQuestion(payloadJson);
        if (question.isEmpty() || answer.contains(question)) {
            return answer;
        }
        return answer.isEmpty() ? question : question + "\n\n" + answer;
    }

    /**
     * 取卡片 JSON 里的 answer 字段；解析失败按空处理。
     *
     * @param payloadJson 卡片正文
     * @return 已流出正文
     */
    private String answerOf(String payloadJson) {
        try {
            return JSON.readTree(payloadJson).path("answer").asText("").trim();
        } catch (Exception ex) {
            return "";
        }
    }

    /**
     * 取等待卡选项协议里的问句（{@code choice.question}）；解析失败或没有选项时为空。
     *
     * @param payloadJson 卡片正文
     * @return 问句原文
     */
    private static String choiceQuestion(String payloadJson) {
        try {
            return JSON.readTree(payloadJson).path("choice").path("question").asText("").trim();
        } catch (Exception ex) {
            return "";
        }
    }

    /**
     * 把库里最后一张工作流卡改成「已中断」，并补一条「已中断」闭环。
     *
     * <p>为什么改写已落库的卡片而不是直接用内存快照：等待期间这一轮早已结束、快照已清空，
     * 库里那份才是回放的唯一来源。改写成新事件后，前端按同编码原位覆盖最后一张卡。
     *
     * @param sessionId 会话主键
     * @param run       被取消的 run（取主键做摘要）
     * @param name      工作流展示名
     * @param code      工作流编码
     */
    private void appendCancelledCard(long sessionId, WorkflowRun run, String name, String code) {
        String payload = "";
        try {
            payload = events.listBySessionId(sessionId).stream()
                    .filter(row -> row != null && EventType.WORKFLOW.code().equalsIgnoreCase(row.getEventType()))
                    .map(RunEvent::getContentText)
                    .filter(text -> text != null && !text.isBlank())
                    // 只改写同一张卡：编码优先、名字兜底，避免把别的工作流的卡改成中断
                    .filter(text -> sameCard(text, name, code))
                    .reduce((first, second) -> second)
                    .map(WorkflowRunJson::cancelled)
                    .orElse("");
        } catch (RuntimeException ex) {
            log.warn("停止等待回复时读取历史卡片失败 runId={} type={}",
                    run.getId(), ex.getClass().getSimpleName());
        }
        if (!payload.isBlank()) {
            try {
                writer.append(sessionId, events.nextSeq(sessionId), EventType.WORKFLOW, "已中断：" + name,
                        payload, null, null);
            } catch (RuntimeException ex) {
                // 卡片改写失败不能把「已取消」回滚：run 已经是终态，最多回放少一条中断卡
                log.warn("停止等待回复时补写卡片失败 runId={} type={}",
                        run.getId(), ex.getClass().getSimpleName());
            }
        }
        // 闭环说明：刷新后 hasOpenTurn 看到它就停止续订，页面落在中断终态
        try {
            writer.append(sessionId, events.nextSeq(sessionId), EventType.ERROR, "已中断", null, null);
        } catch (RuntimeException ex) {
            log.warn("停止等待回复时补写闭环失败 runId={} type={}",
                    run.getId(), ex.getClass().getSimpleName());
        }
    }

    /**
     * 卡片是否就是这一张：编码优先（更稳），空编码时按名字比。
     *
     * @param payloadJson 卡片正文
     * @param name        工作流展示名
     * @param code        工作流编码
     * @return true 表示同一张卡
     */
    private static boolean sameCard(String payloadJson, String name, String code) {
        try {
            var node = JSON.readTree(payloadJson);
            String cardCode = node.path("code").asText("").trim();
            if (!code.isEmpty()) {
                return code.equals(cardCode);
            }
            return name.equals(node.path("name").asText("").trim());
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 等待回复中的工作流（会话级）：点选项与输入框提交共用同一个目标。
     *
     * @param sessionId 工作台会话 id
     * @return 等待中的 run；没有为空
     */
    public Optional<WorkflowRun> waitingRun(long sessionId) {
        return workflowRuns.findWaitingReplyRun(sessionId);
    }

    /**
     * 校验 optionId 是否属于该运行等待节点给出的选项。
     *
     * @param run      等待中的运行
     * @param optionId 前端回传的选项标识
     * @return 服务端登记的选项文案；不匹配为空
     */
    public Optional<String> waitingOptionLabel(WorkflowRun run, String optionId) {
        if (run == null) {
            return Optional.empty();
        }
        return workflowRuns.waitingOptionLabel(run.getId(), optionId);
    }
    /**
     * 点选项续跑（等待工作流专用）：与 {@link #resumeIfWaiting} 同一套写入，只是回复文案以服务端登记为准。
     *
     * <p>为什么单独一个公开入口：HTTP 层要先把「点的是不是这一步给出的选项」校验掉，
     * 校验不通过就应按单专家的原语义回 400，而不是静默吞掉。校验通过后直接复用内部续跑。
     *
     * @param sessionId 工作台会话 id
     * @param run       已确认等待中的运行
     * @param label     服务端登记的选项文案（作为 input.reply）
     * @param userId    会话归属人
     * @param progress  本轮增量出口；可空
     * @return 续跑结果；运行已被并发收口时为空
     */
    public Optional<Resumed> resumeWithLabel(long sessionId, WorkflowRun run, String label, long userId,
                                             TrialProgress progress) {
        return resumeWaiting(sessionId, run, label, userId, progress);
    }

    /**
     * 用输入框原文续跑已确认的等待运行。
     *
     * <p>与点选共用同一条写入：回复进入同一条 run 的 {@code input.reply}，不新建运行。
     * 调用方已定位到等待中的 run（避免重复查询后目标在两次读取之间被并发收口）。
     *
     * @param sessionId 工作台会话 id
     * @param run       已确认等待中的运行
     * @param reply     用户输入原文
     * @param userId    会话归属人
     * @param progress  本轮增量出口；可空
     * @return 续跑结果；运行已被并发收口时为空
     */
    public Optional<Resumed> resumeWaiting(long sessionId, WorkflowRun run, String reply, long userId,
                                           TrialProgress progress) {
        if (run == null || reply == null || reply.isBlank()) {
            return Optional.empty();
        }
        return resume(sessionId, run, reply, userId, progress);
    }

    /**
     * 把用户这一句送回等待中的运行并续跑；没有等待中的运行时不产生任何副作用。
     *
     * @param sessionId 工作台会话 id
     * @param reply     用户这一轮正文（去空白后非空）
     * @param userId    会话归属人
     * @param progress  本轮增量出口；可空（同步 send 路径）
     * @return 续跑结果；没有等待中的运行时为空
     */
    public Optional<Resumed> resumeIfWaiting(long sessionId, String reply, long userId, TrialProgress progress) {
        if (reply == null || reply.isBlank()) {
            return Optional.empty();
        }
        Optional<WorkflowRun> found = workflowRuns.findWaitingReplyRun(sessionId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        return resume(sessionId, found.get(), reply, userId, progress);
    }
    /**
     * 续跑实现：点选与输入框提交共用这一条，写入字段统一是 {@code input.reply}。
     *
     * @param sessionId 工作台会话 id
     * @param run       已确认等待中的运行
     * @param reply     用户这一句（输入原文，或选项的服务端权威文案）
     * @param userId    会话归属人
     * @param progress  本轮增量出口；可空
     * @return 续跑结果；运行已被并发收口时为空
     */
    private Optional<Resumed> resume(long sessionId, WorkflowRun run, String reply, long userId,
                                     TrialProgress progress) {
        WorkflowDef def = workflowRuns.definitionOf(run).orElse(null);
        String name = displayName(def, run);
        String code = def == null || def.getCode() == null ? "" : def.getCode().trim();
        // 身份与首次发起同一个算法族（depth=0 / 根父键 / 工作流编码作叶子键），前端投影无需特判
        ParticipantView participant = new ParticipantView(
                "p-0-root-" + (code.isEmpty() ? "wf-" + run.getId() : code),
                name, ParticipantView.KIND_WORKFLOW, code.isEmpty() ? null : code, 0);

        // reply 交给投影：续跑后询问节点收成完成态，节点里记下这次选择（不再另发用户消息）
        WorkflowCardProjector projector = new WorkflowCardProjector(name, code, progress, json, true,
                previousAnswer(sessionId, code, name), reply);
        // 续跑后的实现节点才会写文件：范围用这一张卡的身份，沙箱用本会话；
        // 节点内过程同样回填到这张卡，直播与回放读同一份
        WorkflowAgentFileScope.open(progress, participant.id(), participant.name(), sessionId, projector);
        WorkflowRunView view;
        String text;
        List<GeneratedFile> files = List.of();
        try {
            if (progress != null) {
                progress.beginParticipant(participant);
            }
            // 续跑入口返回续跑后的详情：状态以库里读回的为准，不再二次查询
            WorkflowRun latest = workflowRuns.resumeWithReply(run.getId(), reply, userId, projector).getRun();
            text = WorkflowOutcomeTexts.assistantText(def, latest, json);
            view = projector.finish(latest, text);
        } catch (BizException ex) {
            /*
             * 运行刚被并发收口（取消 / 别人已经回复过）：不冒充续跑成功，
             * 也不在这里抛错打断用户这一句——调用方会按普通一轮继续处理。
             */
            log.info("工作流对话续跑跳过 runId={} sessionId={} reason={}",
                    run.getId(), sessionId, ex.getMessage());
            return Optional.empty();
        } catch (RuntimeException ex) {
            log.warn("工作流对话续跑失败 runId={} sessionId={} type={}",
                    run.getId(), sessionId, ex.getClass().getSimpleName());
            /*
             * 不让直播与回放说两套话：先看库里这条运行到底走到哪了。
             * 仍停在等待 → 续跑没生效，卡片保持等待并如实说明；已经不在等待 → 按失败提示，
             * 由用户去运行详情看真实终态，不在这里伪造一次成功。
             */
            WorkflowRun fresh = workflowRuns.findWaitingReplyRun(sessionId).orElse(null);
            if (fresh != null) {
                text = "工作流「" + name + "」这次回复没有生效，运行仍停在等待；请再发一次，或点停止结束这条运行。";
                view = projector.finish(fresh, text);
            } else {
                text = "工作流「" + name + "」续跑时未能完成，请到「编排 → 工作流」查看运行详情。";
                view = projector.finishFailed(text);
            }
        } finally {
            // 先取走再关：关掉之后清单就空了。失败路径也保留已经写出的文件。
            files = WorkflowAgentFileScope.snapshot();
            WorkflowAgentFileScope.close();
        }
        if (progress != null) {
            progress.workflow(view);
        }
        List<RunEvent> written = appendTimeline(sessionId, participant, view, text, files);
        return Optional.of(new Resumed(turnOf(view.answer()), written));
    }

    /**
     * 把这一轮落成与首次发起同序的事件：身份 begin → 工作流卡 → 助手正文。
     *
     * <p>顺序不能反：回放靠 begin 把卡片挂到「工作流」这块上；末尾必须有一条
     * 助手正文，否则 {@code hasOpenTurn} 会认为这一轮还没写完、刷新后一直续订。
     *
     * @param sessionId   会话主键
     * @param participant 工作流身份
     * @param view        卡片终态 / 挂起态
     * @param text        本轮正文
     * @param files       本段写出的源码文件；可空。落成既有 FILE 事件，助手正文仍放在最后
     * @return 已落库事件（按写入顺序）
     */
    private List<RunEvent> appendTimeline(long sessionId, ParticipantView participant,
                                          WorkflowRunView view, String text, List<GeneratedFile> files) {
        List<RunEvent> written = new ArrayList<>();
        String identity = ParticipantJson.payload(participant, true);
        if (identity != null) {
            written.add(writer.append(sessionId, events.nextSeq(sessionId), EventType.PARTICIPANT,
                    participant.name(), identity, null, null));
        }
        // 落库只存骨架：节点产出留在 workflow_node_run，刷新回放按 runId 现算
        String payload = WorkflowRunJson.compact(view);
        if (!payload.isEmpty()) {
            written.add(writer.append(sessionId, events.nextSeq(sessionId), EventType.WORKFLOW,
                    WorkflowRunJson.summary(view), payload, null, null));
        }
        /*
         * 末尾必须有助手正文：没有它 hasOpenTurn 会认为这一轮还没写完，刷新后一直续订。
         * 卡片自带正文时用它（与回放同一份）；确实为空时给一句明确的等待 / 终态说明兜底。
         */
        if (files != null) {
            for (GeneratedFile file : files) {
                if (file == null || file.fileName() == null || file.fileName().isBlank()) {
                    continue;
                }
                // 与首次发起同一份 FILE 正文：回放按参与者把文件挂回这张卡
                String summary = file.fileName() + "（" + file.humanSize() + "）";
                written.add(writer.append(sessionId, events.nextSeq(sessionId), EventType.FILE, summary,
                        GeneratedFileMarker.payload(file.fileName(), file.size(),
                                file.participantId(), file.participantName()), null, null));
            }
        }
        String body = text == null || text.isBlank() ? fallbackBody(view) : text;
        written.add(writer.append(sessionId, events.nextSeq(sessionId), EventType.ASSISTANT, body, null, null));
        return written;
    }

    /**
     * 兜底正文：卡片没有正文时也要让这一轮写完，而不是留下一个永远续订的空轮。
     *
     * @param view 卡片视图
     * @return 一句明确说明
     */
    private static String fallbackBody(WorkflowRunView view) {
        String status = view == null ? "" : (view.status() == null ? "" : view.status().trim().toUpperCase());
        if ("WAITING_REPLY".equals(status)) {
            return "工作流正在等待你的回复，请直接在本会话下方输入框接着说。";
        }
        if ("PAUSED_HUMAN".equals(status)) {
            return "工作流已运行到人工处理节点，正在等待人工处理。";
        }
        return "工作流本轮没有返回正文，请到「编排 → 工作流」查看运行详情。";
    }

    /**
     * 工作流展示名：名称 → 编码 → 「工作流 #id」；与卡片口径一致，不编别名。
     *
     * @param def 定义；定义已删除时为空
     * @param run 运行实例
     * @return 非空展示名
     */
    private static String displayName(WorkflowDef def, WorkflowRun run) {
        if (def != null && def.getName() != null && !def.getName().isBlank()) {
            return def.getName().trim();
        }
        if (def != null && def.getCode() != null && !def.getCode().isBlank()) {
            return def.getCode().trim();
        }
        return "工作流 #" + (run == null || run.getWorkflowId() == null ? "?" : run.getWorkflowId());
    }

    /** 单轮正文：工作流不产 token 账，计量仍在外层模板。 */
    private static TrialTurn turnOf(String text) {
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText(text == null ? "" : text);
        return turn;
    }
}
