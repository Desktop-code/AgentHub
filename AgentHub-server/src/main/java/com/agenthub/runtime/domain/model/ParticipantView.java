package com.agenthub.runtime.domain.model;

/**
 * 一轮运行里某个参与者的身份。所属域：runtime。
 *
 * <p>为什么需要它：并行 / 嵌套执行时，增量是交错到达的。旧实现靠「最后一次 route 文案」
 * 猜这一段思考属于谁，于是每来一个思考字都要重推一遍同一句「路由到 …」，
 * 方案自己的思考与子专家的事件就缠在一起。现在每个增量都带明确的参与者 id，
 * 前端按 id 分块，不再从文案反推归属。
 *
 * <p>硬约束：
 * <ul>
 *   <li>{@code id} 在同一轮内唯一且稳定（入口 / 方案 / 子专家 / 汇总各一条）；</li>
 *   <li>{@code name} 必须是配置 / 路由事件里的展示名，前端据此读形象，不在投影层写死别名；</li>
 *   <li>{@code kind} 只用于分块与角标，不参与路由结果，也不落任何业务表。</li>
 * </ul>
 *
 * @param id       参与者稳定标识（同一轮唯一）
 * @param name     展示名（配置名 / 子专家名 / 方案名）
 * @param kind     参与者类型，见 {@link #KIND_GATEWAY} 等常量
 * @param routeKey 路由键；入口与汇总为空
 * @param depth    嵌套层级：0＝入口，1＝入口之下，依次递增
 */
public record ParticipantView(String id, String name, String kind, String routeKey, int depth) {

    /** 入口判定（对话入口）：只出判定推理与判定句 */
    public static final String KIND_GATEWAY = "gateway";
    /** 方案分流：只出分流推理与分流句，不承接子专家的思考 / 工具 */
    public static final String KIND_SCHEME = "scheme";
    /** 子专家 / 固定模板成员 */
    public static final String KIND_AGENT = "agent";
    /** 工作流目标：只报执行状态，不展开思考 */
    public static final String KIND_WORKFLOW = "workflow";
    /** 真汇总：汇总模型自己的推理与结论，排在所有子专家之后 */
    public static final String KIND_CONCLUSION = "conclusion";

    /** 归一化：空串与非法层级不应把展示层打崩。 */
    public ParticipantView {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        kind = kind == null || kind.isBlank() ? KIND_AGENT : kind;
        depth = Math.max(0, depth);
    }

    /**
     * 入口参与者。
     *
     * @param id    稳定标识
     * @param name  入口展示名（配置名）
     * @return 入口身份
     */
    public static ParticipantView gateway(String id, String name) {
        return new ParticipantView(id, name, KIND_GATEWAY, null, 0);
    }

    /**
     * 方案参与者。
     *
     * @param id       稳定标识
     * @param name     方案展示名
     * @param routeKey 路由键
     * @param depth    嵌套层级
     * @return 方案身份
     */
    public static ParticipantView scheme(String id, String name, String routeKey, int depth) {
        return new ParticipantView(id, name, KIND_SCHEME, routeKey, depth);
    }

    /**
     * 子专家参与者。
     *
     * @param id       稳定标识
     * @param name     子专家展示名
     * @param routeKey 路由键
     * @param depth    嵌套层级
     * @return 专家身份
     */
    public static ParticipantView agent(String id, String name, String routeKey, int depth) {
        return new ParticipantView(id, name, KIND_AGENT, routeKey, depth);
    }

    /**
     * 真汇总参与者。
     *
     * @param id   稳定标识
     * @param name 汇总记录展示名（通常是方案名）
     * @return 汇总身份
     */
    public static ParticipantView conclusion(String id, String name) {
        return new ParticipantView(id, name, KIND_CONCLUSION, null, 0);
    }
}
