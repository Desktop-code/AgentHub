package com.agenthub.runtime.domain.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 方案「停在某个成员处等用户回复」时的完整可恢复状态。所属域：runtime。
 *
 * <p>最小可续跑集合：这一轮的用户原文与模型输入、停在第几位（pausedMember / remaining）、
 * 问句与选项（choice）、刚才那位已经答出的正文（pausedAnswer，续跑时作为上一段结论交回）、
 * 已经跑完且已落库的成员（done + writtenKeys）。
 *
 * <p>writtenKeys 的语义：这些路由键的「身份行 + 路由行」已经写进会话时间线。
 * 停等补写时据此跳过已完成成员；续跑成功时同一位被问专家的 begin 不能再写第二次，
 * 只追加它这次续跑新产生的思考 / 终稿，卡片才不会分裂成两张。
 */
@Data
public class MaPauseSnapshot {

    /** 会话主键 */
    private Long sessionId;
    /** 方案定义 id；旧 Agent 固定串行没有方案时为空 */
    private Long maDefId;
    /** 方案展示名（「方案结论：X」与停止文案用） */
    private String schemeName;
    /** 当前模板：FIXED_SEQUENCE / ROUTER_THEN_SEQUENCE；续跑只支持这两类串行 */
    private String template;
    /** 嵌套层级；本刀只支持 0 */
    private int depth;
    /** 当前层方案路由键；根层为空 */
    private String parentKey;
    /** 发出询问的那位成员（含其完整 turn 与身份） */
    private MaPauseMember pausedMember;
    /** 发出询问的成员刚才答出的正文；续跑时作为「上一段结论」交回 */
    private String pausedAnswer;
    /** 问句 + 选项；waiting=true */
    private AgentChoice choice;
    /** 已经跑完的成员（含完整 turn，用于停等补写与续跑前置摘要） */
    private List<MaPauseMember> done = new ArrayList<>();
    /** 还没跑的成员（只带路由信息） */
    private List<MaPauseMember> remaining = new ArrayList<>();
    /** 超出上限被跳过的成员（保留给汇总与再次停等） */
    private List<MaPauseMember> skipped = new ArrayList<>();
    /** 本次停等之前就已经写进时间线的路由键（再停等时用于去重） */
    private Set<String> preWrittenKeys = new LinkedHashSet<>();
    /**
     * {@link #done} 前缀里「上一次停等已经完整写过」的成员数。
     * 再次停等时这段只作为续跑前置摘要，不再补写正文；其后才是本轮新完成、需要补写事件的成员。
     */
    private int seedDoneSize;
    /** 本次停等之后应当视为「已写身份 / 路由」的路由键（落库 + 续跑去重用） */
    private Set<String> writtenKeys = new LinkedHashSet<>();
    /** 本轮用户原文（续跑时仍作为整体目标） */
    private String userText;
    /** 本轮送给模型的用户侧文本（含记忆 / 近轮；续跑时原样复用） */
    private String modelUser;
    /** 入口参与者身份；停等补写时据此还原「对话入口」块，续跑不再重写 */
    private ParticipantView gateway;
    /** 入口判定 / 方案分流推理；首轮停等落库，续跑不重复 */
    private String judgeReasoning;
    /** 多意图决策块（机器 JSON）；与普通轮同口径，续跑不重复 */
    private String decisionBlock;

    /** 防御性归一：null 列表 / 集合 → 空容器。反序列化与装配都走这里。 */
    public MaPauseSnapshot() {
        done = new ArrayList<>();
        remaining = new ArrayList<>();
        skipped = new ArrayList<>();
        preWrittenKeys = new LinkedHashSet<>();
        writtenKeys = new LinkedHashSet<>();
    }

    /**
     * @return 发出询问的成员路由键；没有时为空串
     */
    public String pausedKey() {
        return pausedMember == null || pausedMember.getRouteKey() == null
                ? "" : pausedMember.getRouteKey().trim();
    }

    /**
     * @return 发出询问的成员参与者 id；没有时为空串
     */
    public String pausedParticipantId() {
        return pausedMember == null || pausedMember.getParticipantId() == null
                ? "" : pausedMember.getParticipantId().trim();
    }
}
