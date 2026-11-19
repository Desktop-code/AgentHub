package com.agenthub.runtime.domain.model;

/**
 * 试跑步骤类型。所属域：runtime。
 * 取值写在 agent_run_event.event_type（VARCHAR），不改列类型。
 */
public enum EventType {

    /** 用户本轮输入 */
    USER("user"),
    /** 引擎 ThinkingBlock。没有中间思考时不写，禁止占位 */
    REASONING("reasoning"),
    /** 助手回复。正文是 Markdown，放 content_text */
    ASSISTANT("assistant"),
    /** 工具调用。摘要是名称和脱敏参数，密钥原文不入库 */
    TOOL("tool"),
    /**
     * 图片文字提取结果。正文是逐图汇总的识别文本。
     * 单独成类型而不塞进 SYSTEM：时间线上要能把「这张图提了哪些字」与装配说明区分开，
     * 用户才能判断是识别错了还是主模型答错了。
     */
    VISION("vision"),
    /**
     * 入口 Router 在意图分流前的附件预处理。
     * 正文是 {@code PreprocessJson}：进行中标明识图 / 抽文件 / 转写，完成后回显摘要。
     * 没有附件时不写，避免空步骤。
     */
    PREPROCESS("preprocess"),
    /** Router 选中了哪个子 Agent */
    ROUTE("route"),
    /**
     * 参与者开始（入口 / 方案 / 专家 / 汇总）。正文是身份的 JSON：
     * id + name + kind + routeKey + depth。
     *
     * <p>为什么要落库：刷新后要按同一套身份把块还原出来，不能再靠 route 文案反推归属。
     * 进程内的内存快照也用它标记「这一步已经开始」，前端据此立刻出块。
     */
    PARTICIPANT("participant"),
    /**
     * 计划执行模式的结构化计划。正文是 PlanView 的 JSON：
     * planId + state + steps[{seq,title,detail}]；状态变更事件不再重复 steps。
     */
    PLAN("plan"),
    /**
     * 计划中单步的开始/结束。正文是 StepView 的 JSON：
     * seq + title + state + text（该步结果，仅结束时非空）。
     */
    STEP("step"),
    /**
     * 需要用户拍板的结构化选项。正文是 AgentChoice 的 JSON：
     * question + options[{id,label}]。
     *
     * <p>为什么不塞进 ASSISTANT 正文：选项是机器数据，不该出现在聊天文本里；
     * 独立成事件后，前端只按它渲染 chips，刷新回放也走同一份结构化数据，
     * 不再靠正则从正文里猜列表。
     */
    CHOICE("choice"),
    /** 装配说明，例如源不可用、超预算未暴露或被截断的 Skill */
    SYSTEM("system"),
    /**
     * 本轮生成的工作区文件。正文是 {@code {"fileName":"...","size":123}}（见 GeneratedFileMarker）。
     * 单独成类型而不混进 TOOL：产物要能在聊天区渲染成下载卡片，
     * 且刷新后回放也必须有，而 TOOL 正文会被截断。
     */
    FILE("file"),
    /**
     * 工作流目标的执行卡片快照。正文是 {@code WorkflowRunView} 的 JSON：
     * name + code + status + answer + nodes[{code,name,type,status,note}]。
     *
     * <p>为什么单独成类型：工作流不是一位「专家」，它有自己的执行卡片（节点时间轴 + 产物区）。
     * 只靠一条「运行工作流「X」…」的状态句，刷新后就只剩入口判定，卡片整个消失。
     * 独立事件让实时帧、内存快照与落库回放共用同一份结构，也避免把节点进度塞进聊天正文。
     */
    WORKFLOW("workflow"),
    /** 失败说明，已脱敏 */
    ERROR("error");

    private final String code;

    EventType(String code) {
        this.code = code;
    }

    /**
     * @return 入库值
     */
    public String code() {
        return code;
    }
}
