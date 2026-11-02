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
    /** Router 选中了哪个子 Agent */
    ROUTE("route"),
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
    /** 装配说明，例如源不可用、超预算未暴露或被截断的 Skill */
    SYSTEM("system"),
    /**
     * 本轮生成的工作区文件。正文是 {@code {"fileName":"...","size":123}}（见 GeneratedFileMarker）。
     * 单独成类型而不混进 TOOL：产物要能在聊天区渲染成下载卡片，
     * 且刷新后回放也必须有，而 TOOL 正文会被截断。
     */
    FILE("file"),
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
