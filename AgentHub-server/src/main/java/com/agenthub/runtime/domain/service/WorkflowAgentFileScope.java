package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.AgentChoice;
import com.agenthub.runtime.domain.model.ChildPlanPendingView;
import com.agenthub.runtime.domain.model.GeneratedFile;
import com.agenthub.runtime.domain.model.ParticipantView;
import com.agenthub.runtime.domain.model.PlanView;
import com.agenthub.runtime.domain.model.PreprocessView;
import com.agenthub.runtime.domain.model.StepView;
import com.agenthub.runtime.domain.model.WorkflowRunView;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流 Agent 节点把工作区文件交回工作台对话的范围。所属域：runtime。
 *
 * <p>职责：工作流节点复用现成的 {@code workspaceWriteFile}。工具成功后运行时已经
 * 收进 {@code TrialTurn.files}，但节点执行原来把这份清单丢掉，对话里就只剩正文。
 * 本范围在「工作台发起 / 续跑」这一段把清单接出来：直播只转发文件帧，落库仍走
 * 既有 FILE 事件，不另做附件协议，也不改卡片投影。
 *
 * <p>关键约束：
 * <ul>
 *   <li>只在工作台线程上打开；工作流页单独运行不打开，沙箱仍用 runId；</li>
 *   <li>工具回调在别的线程上，只拿构造时留下的进度对象，不读本 ThreadLocal；</li>
 *   <li>子专家的正文 / 工具过程不往外推，避免和已经流出的工作流卡正文叠成两份。</li>
 * </ul>
 *
 * <p>线程：打开与关闭必须在同一次同步执行的同一线程；用完在 finally 里清掉，
 * 池化线程不能把上一轮的会话带进下一次。
 */
public final class WorkflowAgentFileScope {

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private WorkflowAgentFileScope() {
    }

    /**
     * 打开本段工作流执行的文件范围。
     *
     * @param upstream         工作台增量出口；可空（只收集、不直播）
     * @param ownerId          工作流参与者 id，与开跑时的 begin 同一套；空则不署名
     * @param ownerName        工作流展示名；与卡片标题一致，文件才挂得上这张卡
     * @param sandboxSessionId 工作台会话主键；小于等于 0 时沙箱回落到 runId
     */
    public static void open(TrialProgress upstream, String ownerId, String ownerName, long sandboxSessionId) {
        open(upstream, ownerId, ownerName, sandboxSessionId, null);
    }

    /**
     * 打开本段工作流执行的文件范围，并把节点内过程交给卡片投影。
     *
     * @param upstream         工作台增量出口；可空（只收集、不直播）
     * @param ownerId          工作流参与者 id，与开跑时的 begin 同一套；空则不署名
     * @param ownerName        工作流展示名；与卡片标题一致，文件才挂得上这张卡
     * @param sandboxSessionId 工作台会话主键；小于等于 0 时沙箱回落到 runId
     * @param stepSink         节点内思考 / 工具 / 正文 / 文件的落点；可空（不展示节点过程）
     */
    public static void open(TrialProgress upstream, String ownerId, String ownerName, long sandboxSessionId,
                            WorkflowNodeStepSink stepSink) {
        Scope scope = new Scope();
        scope.upstream = upstream;
        scope.ownerId = ownerId == null ? "" : ownerId.trim();
        scope.ownerName = ownerName == null ? "" : ownerName.trim();
        scope.sandboxSessionId = sandboxSessionId;
        scope.stepSink = stepSink;
        CURRENT.set(scope);
    }

    /**
     * 结束本段范围。没有打开时也安全。
     */
    public static void close() {
        CURRENT.remove();
    }

    /**
     * 沙箱会话：工作台打开范围时用工作台会话，下载地址才和写文件目录一致；
     * 否则用调用方原来的 runId。
     *
     * @param fallbackRunId 工作流 run 主键
     * @return 交给 ReAct 的会话标识
     */
    public static long sandboxSessionId(long fallbackRunId) {
        Scope scope = CURRENT.get();
        if (scope == null || scope.sandboxSessionId <= 0) {
            return fallbackRunId;
        }
        return scope.sandboxSessionId;
    }

    /**
     * 只转发文件的进度。没有打开范围时返回空，执行器按「不推增量」处理。
     *
     * @return 进度；未打开时为空
     */
    public static TrialProgress fileProgress() {
        Scope scope = CURRENT.get();
        if (scope == null) {
            return null;
        }
        return new FilesOnly(scope);
    }

    /**
     * 当前节点的执行进度：文件照旧转发，思考 / 工具 / 正文按节点编码交给卡片投影。
     *
     * <p>没有打开范围（工作流页单独运行 / 窄测）时返回 null，执行器按「不推增量」处理。
     * 没有步骤落点时退回只转发文件的旧行为，绝不把过程丢进整张卡的正文。
     *
     * @param nodeCode 当前执行的节点编码；可空
     * @return 进度；未打开范围时为空
     */
    public static TrialProgress nodeProgress(String nodeCode) {
        Scope scope = CURRENT.get();
        if (scope == null) {
            return null;
        }
        if (scope.stepSink == null || nodeCode == null || nodeCode.isBlank()) {
            return new FilesOnly(scope);
        }
        return new NodeSteps(scope, nodeCode.trim());
    }

    /**
     * 节点收口时再补一次文件清单：实时文件回调可能在工具线程上，这里保证节点下不漏文件。
     *
     * <p>重复投递由卡片投影按文件名去重，不影响展示；没有打开范围或没有落点时静默跳过。
     *
     * @param nodeCode 节点编码
     * @param files    本节点产出的文件；可空
     */
    public static void collectForNode(String nodeCode, List<GeneratedFile> files) {
        Scope scope = CURRENT.get();
        if (scope == null || scope.stepSink == null || nodeCode == null || nodeCode.isBlank()
                || files == null || files.isEmpty()) {
            return;
        }
        for (GeneratedFile file : files) {
            if (file == null || file.fileName() == null || file.fileName().isBlank()) {
                continue;
            }
            try {
                scope.stepSink.file(nodeCode.trim(), file.fileName(), file.size());
            } catch (RuntimeException ex) {
                // 展示是旁路：落点异常不能把一次正常的节点执行变成失败
            }
        }
    }

    /**
     * 把本节点刚写出的文件收进范围。已带作者的不改写。
     *
     * @param files 节点产出的文件；可空
     */
    public static void collect(List<GeneratedFile> files) {
        Scope scope = CURRENT.get();
        if (scope == null || files == null || files.isEmpty()) {
            return;
        }
        for (GeneratedFile file : files) {
            if (file == null || file.fileName() == null || file.fileName().isBlank()) {
                continue;
            }
            // 产生处署名：缺作者时按本工作流补上，已经署名的内层文件保持原作者
            GeneratedFile owned = scope.ownerId.isEmpty() ? file : file.withOwner(scope.ownerId, scope.ownerName);
            scope.files.add(owned);
        }
    }

    /**
     * 取走本段已收集的文件。未打开时为空表。
     *
     * @return 不可变快照
     */
    public static List<GeneratedFile> snapshot() {
        Scope scope = CURRENT.get();
        if (scope == null || scope.files.isEmpty()) {
            return List.of();
        }
        return List.copyOf(scope.files);
    }

    /** 一次打开期间的可变状态。只活在打开它的那条线程上。 */
    private static final class Scope {
        private TrialProgress upstream;
        private String ownerId = "";
        private String ownerName = "";
        private long sandboxSessionId;
        /** 节点内过程落点；可空。空时不展示思考 / 工具，只保留文件转发。 */
        private WorkflowNodeStepSink stepSink;
        private final List<GeneratedFile> files = new ArrayList<>();
    }

    /**
     * 吞掉子专家的正文和工具过程，只把文件帧交给工作台。
     * 工作流卡的文字仍由节点完成时追加，这里再推 token 会和已流出的正文叠在一起。
     */
    private static final class FilesOnly implements TrialProgress {

        private final Scope scope;

        private FilesOnly(Scope scope) {
            this.scope = scope;
        }

        @Override
        public void phase(String phase) {
            // 子专家阶段不进工作流卡
        }

        @Override
        public void route(String summary) {
            // 子专家路由句不进工作流卡
        }

        @Override
        public void reasoning(String delta) {
            // 子专家思考不进工作流卡
        }

        @Override
        public void tool(String name, String arguments, String result, boolean success) {
            // 工具过程不进工作流卡；文件走 file()
        }

        @Override
        public void assistant(String delta) {
            // 子专家 token 不进对话；节点完成时工作流卡会追加成文结论
        }

        /**
         * 文件帧：先按本工作流署名，再交给工作台。出站失败不打断节点。
         */
        @Override
        public void file(GeneratedFile file) {
            if (file == null || scope.upstream == null) {
                return;
            }
            GeneratedFile owned = scope.ownerId.isEmpty() ? file : file.withOwner(scope.ownerId, scope.ownerName);
            try {
                scope.upstream.file(owned);
            } catch (RuntimeException ex) {
                // 客户端已断开：文件仍会在收尾时落库，不让出站失败变成节点失败
            }
        }

        @Override
        public void choice(AgentChoice choice) {
            // 选项由询问节点的产出字段进工作流卡，不从这里再推一次
        }

        @Override
        public void plan(PlanView plan) {
        }

        @Override
        public void step(StepView step) {
        }

        @Override
        public void workflow(WorkflowRunView view) {
        }

        @Override
        public void vision(String text) {
        }

        @Override
        public void preprocess(PreprocessView view) {
        }

        @Override
        public void childPlanPending(ChildPlanPendingView pending) {
        }

        @Override
        public void beginParticipant(ParticipantView participant) {
        }

        @Override
        public void endParticipant(ParticipantView finished, String state, String note) {
        }
    }

    /**
     * 按节点归属转发执行进度：文件仍走工作台文件帧，思考 / 工具 / 正文交给卡片投影。
     *
     * <p>为什么只在这一层分流：工作流引擎与 Agent 执行器都不该认识「执行卡片」，
     * 它们只看到普通的 {@link TrialProgress}；由范围对象把增量贴上节点编码，
     * 卡片投影才能把内容挂到正确的时间轴节点上。
     */
    private static final class NodeSteps implements TrialProgress {

        private final Scope scope;
        private final String nodeCode;

        private NodeSteps(Scope scope, String nodeCode) {
            this.scope = scope;
            this.nodeCode = nodeCode;
        }

        @Override
        public void phase(String phase) {
            // 阶段切换不单独展示，思考 / 工具 / 正文各自已有落点
        }

        @Override
        public void route(String summary) {
            // 子专家路由句不进工作流卡：路由是入口 / 方案的事
        }

        @Override
        public void reasoning(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            try {
                scope.stepSink.reasoning(nodeCode, delta);
            } catch (RuntimeException ex) {
                // 展示旁路异常不打断 Agent 执行
            }
        }

        @Override
        public void toolStart(String name) {
            try {
                scope.stepSink.toolStart(nodeCode, name);
            } catch (RuntimeException ex) {
                // 展示旁路异常不打断 Agent 执行
            }
        }

        @Override
        public void tool(String name, String arguments, String result, boolean success) {
            try {
                scope.stepSink.tool(nodeCode, name, arguments, result, success);
            } catch (RuntimeException ex) {
                // 展示旁路异常不打断 Agent 执行
            }
        }

        @Override
        public void assistant(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            try {
                scope.stepSink.assistant(nodeCode, delta);
            } catch (RuntimeException ex) {
                // 展示旁路异常不打断 Agent 执行
            }
        }

        @Override
        public void file(GeneratedFile file) {
            if (file == null) {
                return;
            }
            // 文件仍按原路径署名并交给工作台；节点行同时留一份元数据
            if (scope.upstream != null) {
                GeneratedFile owned = scope.ownerId.isEmpty()
                        ? file : file.withOwner(scope.ownerId, scope.ownerName);
                try {
                    scope.upstream.file(owned);
                } catch (RuntimeException ex) {
                    // 客户端已断开：文件仍会在收尾时落库
                }
            }
            if (file.fileName() == null || file.fileName().isBlank()) {
                return;
            }
            try {
                scope.stepSink.file(nodeCode, file.fileName(), file.size());
            } catch (RuntimeException ex) {
                // 展示旁路异常不打断 Agent 执行
            }
        }

        @Override
        public void choice(AgentChoice choice) {
            // 选项由询问节点的产出字段进工作流卡
        }

        @Override
        public void plan(PlanView plan) {
        }

        @Override
        public void step(StepView step) {
        }

        @Override
        public void workflow(WorkflowRunView view) {
        }

        @Override
        public void vision(String text) {
        }

        @Override
        public void preprocess(PreprocessView view) {
        }

        @Override
        public void childPlanPending(ChildPlanPendingView pending) {
        }

        @Override
        public void beginParticipant(ParticipantView participant) {
        }

        @Override
        public void endParticipant(ParticipantView finished, String state, String note) {
        }
    }
}
