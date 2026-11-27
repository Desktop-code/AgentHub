package com.agenthub.runtime.domain.model;

import java.util.List;

/**
 * 工作流目标在工作台消息流里的执行卡片视图。所属域：runtime（展示契约）。
 *
 * <p>为什么不直接暴露工作流域的聚合：消息流只认「卡片要画什么」，
 * 不认 {@code workflow_run} / {@code workflow_node_run} 的字段形状。runtime 侧
 * 只保留这一份稳定契约，实时帧（SSE {@code workflow}）、落库事件（{@link EventType#WORKFLOW}）
 * 与刷新回放共用同一份字段，换引擎实现不必改前端。
 *
 * <p>边界：
 * <ul>
 *   <li>只来自现网真实运行事件（引擎节点状态迁移），<b>没有事件就不补进度</b>；</li>
 *   <li>不携带密钥 / 节点输入输出全文，失败说明只放已脱敏的短摘要；</li>
 *   <li>{@code status} 用大写枚举码（与 {@code WorkflowRunStatus} 同字面量），
 *       前端按它决定卡片的颜色与文案。</li>
 * </ul>
 *
 * @param name   工作流展示名（配置名；为空时前端回落到编码）
 * @param code   工作流编码（可空，仅作附注）
 * @param status 运行状态：CREATED / RUNNING / PAUSED_HUMAN / WAITING_REPLY / SUCCESS / FAILED / CANCELLED
 * @param answer 终答文本（成功后 END 节点抽出的文本，或失败 / 等待人工的说明）；运行中可为空
 * @param nodes  按图顺序的真实节点（已剔除 START / END，它们不是执行步骤）；无事件时为空表
 * @param choice 停等询问的可点选项（与 CHOICE 事件同形）；其它情况为空
 * @param limits 运行时限（整段上限 / 当前节点上限及各自截止）；算不出时为空
 * @param runId  本次工作流运行主键；可空（run 未建起来 / 旧调用点）。
 *               落库卡片只存骨架，刷新回放由服务端按这个主键现算节点产出，
 *               因此它是「事件里存什么」与「读时补全」之间唯一的指针。
 */
public record WorkflowRunView(String name, String code, String status, String answer, List<Node> nodes,
                              Choice choice, Limits limits, Long runId) {

    /** 防御性归一：前端只面对数组，不必逐处判空。 */
    public WorkflowRunView {
        name = name == null ? "" : name.trim();
        code = code == null ? "" : code.trim();
        status = status == null || status.isBlank() ? "RUNNING" : status.trim().toUpperCase();
        answer = answer == null ? "" : answer;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        choice = choice == null || !choice.usable() ? null : choice;
        // 没有截止时刻就等于没有时限：前端据此不画「剩余」那一行
        limits = limits == null || limits.runDeadlineAt() == null ? null : limits;
        // 非正主键一律当「没有指针」：落库时不会写出无意义的 0，读侧也不会去查空 run
        runId = runId == null || runId <= 0L ? null : runId;
    }

    /**
     * 兼容构造：不带运行主键的卡片（实时帧 / 只关心节点状态的旧调用点）。
     * <p>落库路径必须走带 {@code runId} 的那条构造，否则刷新回放无法补全节点产出。
     *
     * @param name   工作流展示名
     * @param code   工作流编码
     * @param status 运行状态
     * @param answer 正文
     * @param nodes  节点视图
     * @param choice 可点选项
     * @param limits 运行时限
     */
    public WorkflowRunView(String name, String code, String status, String answer, List<Node> nodes,
                           Choice choice, Limits limits) {
        this(name, code, status, answer, nodes, choice, limits, null);
    }

    /**
     * 兼容构造：没有时限的卡片（历史回放 / 只关心节点状态的调用点）。
     *
     * @param name   工作流展示名
     * @param code   工作流编码
     * @param status 运行状态
     * @param answer 正文
     * @param nodes  节点视图
     * @param choice 可点选项
     */
    public WorkflowRunView(String name, String code, String status, String answer, List<Node> nodes,
                           Choice choice) {
        this(name, code, status, answer, nodes, choice, null);
    }

    /**
     * 兼容构造：没有可点选项的卡片（绝大多数节点状态帧走这条）。
     *
     * @param name   工作流展示名
     * @param code   工作流编码
     * @param status 运行状态
     * @param answer 正文
     * @param nodes  节点视图
     */
    public WorkflowRunView(String name, String code, String status, String answer, List<Node> nodes) {
        this(name, code, status, answer, nodes, null, null);
    }

    /**
     * 运行时限。所属域：runtime（展示契约）。
     *
     * <p>为什么送「截止时刻」而不是「剩余毫秒」：卡片要能刷新、离开再进、回放。
     * 只要截止不变，前端每次重算的剩余就一致；只送剩余则每一帧都得从服务端重新计时，
     * 刷新后必然「重头再数」。
     *
     * <p>等待不计入：{@code waitingAt} 非空表示当前停在等待，前端以这一刻代替「现在」
     * 计算剩余——等待多久都不会把整段 / 单节点的剩余扣掉。
     *
     * @param runLimitMs     整段上限（毫秒）；<=0 表示未知
     * @param runDeadlineAt  整段截止（epoch 毫秒）
     * @param waitingAt      等待开始时刻（epoch 毫秒）；null 表示正在跑
     * @param nodeCode       当前运行中节点编码；null 表示不展示节点行
     * @param nodeName       当前运行中节点展示名；可空（前端回落编码）
     * @param nodeLimitMs    当前节点上限（毫秒）；null 表示该节点未配置
     * @param nodeDeadlineAt 当前节点截止（epoch 毫秒）；无节点上限时为空
     */
    public record Limits(long runLimitMs, Long runDeadlineAt, Long waitingAt,
                         String nodeCode, String nodeName, Long nodeLimitMs, Long nodeDeadlineAt) {

        /** 防御性归一：上限非正按未知处理；节点上限缺省时连截止一起清掉。 */
        public Limits {
            runLimitMs = Math.max(0L, runLimitMs);
            nodeCode = nodeCode == null || nodeCode.isBlank() ? null : nodeCode.trim();
            nodeName = nodeName == null || nodeName.isBlank() ? null : nodeName.trim();
            if (nodeLimitMs == null || nodeLimitMs <= 0) {
                nodeLimitMs = null;
                nodeDeadlineAt = null;
            }
        }
    }

    /**
     * 停等询问的可点选项。字段与 CHOICE 事件正文完全一致，前端一套渲染。
     *
     * @param question 一句话问句（可空：卡片正文已有问句时前端不再重复）
     * @param options  2~6 个可点项
     */
    public record Choice(String question, List<Option> options) {

        /** 防御性归一：null → 空表。 */
        public Choice {
            question = question == null ? "" : question.trim();
            options = options == null ? List.of() : List.copyOf(options);
        }

        /**
         * @param id    稳定短标识（点选时回传后端定位）
         * @param label 展示文案
         */
        public record Option(String id, String label) {
            public Option {
                id = id == null ? "" : id.trim();
                label = label == null ? "" : label.trim();
            }
        }

        /**
         * 是否够格渲染成一组按钮（与单专家选项同一门槛：2~6 项、id / label 非空且不重复）。
         *
         * @return true 表示可以出芯片
         */
        public boolean usable() {
            if (options.size() < 2 || options.size() > 6) {
                return false;
            }
            for (Option option : options) {
                if (option.id().isEmpty() || option.label().isEmpty()) {
                    return false;
                }
            }
            return options.stream().map(item -> item.id().toLowerCase()).distinct().count() == options.size();
        }
    }

    /**
     * 卡片上的一个执行步骤。
     *
     * <p>本刀起节点不再只有「标题 + 状态」：该节点自己的思考、工具、正文和文件
     * 都挂在节点里展示，避免整段过程 / 结果堆在卡片下面。字段序列化后前端不需要
     * 再从别处拼接，直播与回放读的是同一份。
     *
     * @param code   节点编码（业务唯一）
     * @param name   节点展示名（配置名；为空时前端回落到编码）
     * @param type   节点类型：AGENT / TOOL / CONDITION / HUMAN / PARALLEL_FORK / PARALLEL_JOIN
     * @param status 节点状态：PENDING / RUNNING / SUCCESS / FAILED / SKIPPED / WAITING_HUMAN /
     *               WAITING_REPLY / CANCELLED
     * @param note   一句状态说明（执行中 / 已完成 / 失败原因 / 等待前置节点）；已脱敏
     * @param output 该节点产出的正文（AGENT 节点；等待回复时是问句）；没有为空串
     * @param steps  该节点自己的思考与工具，按发生顺序；没有为空表
     * @param files  该节点写出的工作区文件（只放文件名与体积，下载地址由前端按会话现拼）
     * @param reply  用户对这一节点询问的回复（只挂在被答复的询问节点上）；没有为空串
     * @param attempt 同一节点的第几轮；缺省按 1。循环再次进入时每一轮各占一条，不互相覆盖
     * @param group  所属循环节点编码（展示分组用）；不在任何循环体里为空串。<b>只用于把体节点
     *               圈进同一个循环框</b>，不参与调度；嵌套时取包含它的最内层循环
     * @param loopRound 循环节点自己已经进入的第几轮（读它产出里的 iteration）；非循环节点为 0
     * @param loopTotal 循环节点配置的总次数（读产出里的 maxIterations）；非循环节点为 0
     * @param loopDecision 循环节点最近一次判定：BODY（进入循环体）/ EXIT（次数用尽离开）/
     *                     FAIL（按配置结束为失败）；非循环节点或还没判定为空串
     */
    public record Node(String code, String name, String type, String status, String note,
                       String output, List<Step> steps, List<NodeFile> files, String reply,
                       int attempt, String group, int loopRound, int loopTotal, String loopDecision,
                       String displayCode) {

        /** 防御性归一：状态缺省按「未开始」，文案缺省为空串，列表缺省为空表，轮次至少为 1。 */
        public Node {
            code = code == null ? "" : code.trim();
            displayCode = displayCode == null ? "" : displayCode.trim();
            name = name == null ? "" : name.trim();
            type = type == null ? "" : type.trim().toUpperCase();
            status = status == null || status.isBlank() ? "PENDING" : status.trim().toUpperCase();
            note = note == null ? "" : note;
            output = output == null ? "" : output;
            steps = steps == null ? List.of() : List.copyOf(steps);
            files = files == null ? List.of() : List.copyOf(files);
            reply = reply == null ? "" : reply.trim();
            if (attempt < 1) {
                attempt = 1;
            }
            group = group == null ? "" : group.trim();
            loopRound = Math.max(0, loopRound);
            loopTotal = Math.max(0, loopTotal);
            loopDecision = loopDecision == null ? "" : loopDecision.trim().toUpperCase();
        }

        /**
         * 兼容构造：不带展示编码的调用点按「展示编码同节点编码」处理。
         */
        public Node(String code, String name, String type, String status, String note,
                    String output, List<Step> steps, List<NodeFile> files, String reply,
                    int attempt, String group, int loopRound, int loopTotal, String loopDecision) {
            this(code, name, type, status, note, output, steps, files, reply, attempt,
                    group, loopRound, loopTotal, loopDecision, "");
        }

        /**
         * 兼容构造：不带循环展示字段的调用点按「不在循环体里、没有轮次」处理。
         */
        public Node(String code, String name, String type, String status, String note,
                    String output, List<Step> steps, List<NodeFile> files, String reply,
                    int attempt) {
            this(code, name, type, status, note, output, steps, files, reply, attempt, "", 0, 0, "");
        }

        /**
         * 兼容构造：还没有轮次的调用点按第 1 轮。
         */
        public Node(String code, String name, String type, String status, String note,
                    String output, List<Step> steps, List<NodeFile> files, String reply) {
            this(code, name, type, status, note, output, steps, files, reply, 1);
        }

        /**
         * 兼容构造：还没有节点内过程的旧调用点（取消补状态等）只给五个字段。
         *
         * @param code   节点编码
         * @param name   节点展示名
         * @param type   节点类型
         * @param status 节点状态
         * @param note   状态说明
         */
        public Node(String code, String name, String type, String status, String note) {
            this(code, name, type, status, note, "", List.of(), List.of(), "");
        }
    }

    /**
     * 节点自己的过程步。所属域：runtime（展示契约）。
     *
     * @param type    步骤类型：REASONING（思考） / TOOL（工具调用）
     * @param name    工具名；思考为空串
     * @param content 思考正文，或工具结果摘录；已脱敏、已截断
     * @param state   工具状态：running / done / error；思考为空串
     */
    public record Step(String type, String name, String content, String state) {

        /** 防御性归一：类型与工具状态大写，空值给中性值。 */
        public Step {
            type = type == null ? "" : type.trim().toUpperCase();
            name = name == null ? "" : name.trim();
            content = content == null ? "" : content;
            state = state == null ? "" : state.trim().toUpperCase();
        }
    }

    /**
     * 节点产出的文件元数据。
     *
     * <p>只存文件名与体积：下载地址由前端按「当前会话 + 文件名」现拼，
     * 这样会话复制 / 迁移后不会带着失效的旧地址。
     *
     * @param fileName 相对工作区根的文件名（可带子目录）
     * @param size     字节数；小于 0 表示服务端取不到体积
     */
    public record NodeFile(String fileName, long size) {

        /** 防御性归一：空文件名保留空串，前端据此跳过。 */
        public NodeFile {
            fileName = fileName == null ? "" : fileName.trim();
        }
    }

    /**
     * 是否已经结束（不再需要转圈）。
     *
     * @return true 表示成功 / 失败 / 取消
     */
    public boolean finished() {
        return "SUCCESS".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    /**
     * 是否停在「等用户 / 人处理」的挂起态。前端据此把卡头画成等待而不是转圈运行。
     *
     * @return true 表示 PAUSED_HUMAN / WAITING_REPLY
     */
    public boolean suspended() {
        return "PAUSED_HUMAN".equals(status) || "WAITING_REPLY".equals(status);
    }
}
