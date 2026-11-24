package com.agenthub.runtime.domain.model;

import com.agenthub.runtime.application.router.NestedRoute;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 方案串行执行里的一位成员快照。所属域：runtime。
 *
 * <p>为什么要把「已经跑完的成员」也存下来：方案停等发生在请求线程里，此时整轮的
 * 分段事件还没落库。若只存「停在第几位」，用户刷新后前几位专家的卡片与正文会整段消失。
 * 因此停等时把已完成成员的终稿、思考、工具、文件、工作流卡与内层方案一并序列化，
 * 恢复时按原顺序补写；续跑成功后只追加新成员的段，已写过的键由 {@code writtenKeys} 拦住。
 *
 * <p>participant 字段拆平存：身份是回放挂卡的关键，拆平后前端 / SQL 排查也更直观。
 * 未执行的成员（remaining）只带路由信息，没有 turn / answer。
 */
@Data
public class MaPauseMember {

    /** 路由键（同一方案内稳定；续跑时按它从当前候选里重新取 AgentDef） */
    private String routeKey;
    /** 参与者稳定 id（与直播 begin 同一份，回放按它挂卡） */
    private String participantId;
    /** 参与者展示名 */
    private String participantName;
    /** 参与者类型，见 {@link ParticipantView} 的 KIND_* */
    private String participantKind;
    /** 嵌套层级；本刀只支持根层 0 */
    private int participantDepth;
    /** 交给该成员的子问题（固定串行即用户原问；路由后串行是判路由切分句） */
    private String subQuery;
    /** 判定依据，落库时写进路由行 */
    private String reason;
    /** 路由行（「路由到 …」）；可空 */
    private String routeLine;
    /** 该成员自己的判定推理；只有方案分流成员会有 */
    private String reasoning;
    /** 该成员的成文终答；失败 / 未执行时为空 */
    private String answer;
    /** 失败摘要；成功为 null */
    private String error;
    /** 失败标记（timeout / 配置错误等；跳过不算失败） */
    private boolean failed;
    /** 超出上限未执行的成员 */
    private boolean skipped;
    /** 完整执行结果（思考、工具、文件、token 口径）；未执行时为空 */
    private TrialTurn turn;
    /** 工作流目标的卡片终态；只有工作流成员会非空 */
    private WorkflowRunView workflow;
    /** 目标方案内部各跳；只有 MA 目标会非空 */
    private List<NestedRoute> children = new ArrayList<>();
    /** 目标方案自己的汇总结论名；只有 MA 目标真汇总过才非空 */
    private String conclusionName;
    /** 目标方案汇总模型的推理 */
    private String conclusionReasoning;

    /**
     * 还原成参与者身份。
     *
     * @return 参与者身份；id 为空时返回 null（跳过行没有身份）
     */
    public ParticipantView participant() {
        if (participantId == null || participantId.isBlank()) {
            return null;
        }
        return new ParticipantView(participantId, participantName == null ? "" : participantName,
                participantKind, routeKey, participantDepth);
    }
}
