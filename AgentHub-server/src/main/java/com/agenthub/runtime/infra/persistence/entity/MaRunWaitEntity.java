package com.agenthub.runtime.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * agent_run_ma_wait：方案停在子专家询问处的一行可恢复状态。所属域：runtime。
 *
 * <p>只存「运行停在哪、问了什么、用户回了什么」，不存完整对话（正文仍在 agent_run_event）。
 * 已完成成员的快照以 JSON 存在 done_json / paused_member_json 里，停等时补写成标准事件，
 * 因此回放与直播共用同一套投影，不需要第二套时间线。
 */
@Data
@TableName("agent_run_ma_wait")
public class MaRunWaitEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 会话主键 */
    private Long sessionId;
    /** 方案定义 id；为空表示旧 Agent 固定串行（本刀不落） */
    private Long maDefId;
    /** 方案展示名 */
    private String schemeName;
    /** FIXED_SEQUENCE / ROUTER_THEN_SEQUENCE */
    private String template;
    /** 嵌套层级；本刀只落 0 */
    private Integer depth;
    /** 当前层方案路由键；根层为空 */
    private String parentKey;
    /** 发出询问的成员身份与路由键（拆列便于排查） */
    private String pausedParticipantId;
    private String pausedParticipantName;
    private String pausedParticipantKind;
    private Integer pausedParticipantDepth;
    private String pausedRouteKey;
    /** 发出询问成员的完整快照 */
    private String pausedMemberJson;
    /** 该成员刚才答出的正文 */
    private String pausedAnswer;
    /** 问句 */
    private String question;
    /** 选项 JSON（等待态） */
    private String optionsJson;
    /** 用户答复的展示文案（审计用；事件里另有 CHOICE 回复） */
    private String reply;
    /** 已完成成员列表 JSON */
    private String doneJson;
    /** 待执行成员列表 JSON */
    private String remainingJson;
    /** 已跳过成员列表 JSON */
    private String skippedJson;
    /** 本次停等前已写身份 / 路由的键 */
    private String preWrittenKeysJson;
    /**
     * doneJson 前缀里已经完整写过（身份 + 路由 + 正文）的成员数。
     * 再次停等时只补写这段之后的成员，避免把上一次停等已落库的正文重复追加到错误卡片上。
     */
    private Integer seedDoneSize;
    /** 本次停等后视为已写的键（续跑去重用） */
    private String writtenKeysJson;
    /** 本轮用户原文 */
    private String userText;
    /** 本轮送给模型的用户侧文本 */
    private String modelUser;
    /** 入口参与者身份 JSON（停等补写「对话入口」块用；续跑不重写） */
    private String gatewayJson;
    /** 入口判定 / 方案分流推理（首轮停等落库，续跑不重复） */
    private String judgeReasoning;
    /** 多意图决策块机器 JSON（与普通轮同口径） */
    private String decisionBlock;
    /** WAITING / DONE / CANCELLED */
    private String state;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
