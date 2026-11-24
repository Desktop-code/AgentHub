package com.agenthub.runtime.infra.persistence;

import com.agenthub.runtime.domain.model.AgentChoice;
import com.agenthub.runtime.domain.model.MaPauseMember;
import com.agenthub.runtime.domain.model.MaPauseSnapshot;
import com.agenthub.runtime.domain.repository.MaRunWaitRepository;
import com.agenthub.runtime.domain.service.MaPauseJson;
import com.agenthub.runtime.infra.persistence.entity.MaRunWaitEntity;
import com.agenthub.runtime.infra.persistence.mapper.MaRunWaitMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Optional;

/**
 * agent_run_ma_wait 的持久化实现。所属域：runtime 的 infra。
 *
 * <p>同一会话只保留一条 WAITING：save 先收旧行再插入，避免「上次停等的选项」被下一次
 * 续跑误当成当前询问。历史行不删，保留停等轨迹；查询只取最新 WAITING。
 * 所有 JSON 列都经 {@link MaPauseJson} 读写，应用层不感知 Jackson。
 */
@Repository
public class MaRunWaitRepositoryImpl implements MaRunWaitRepository {

    private final MaRunWaitMapper mapper;
    private final MaPauseJson pauseJson;

    /**
     * @param mapper    表映射
     * @param pauseJson 成员 / 选项 JSON 读写
     */
    public MaRunWaitRepositoryImpl(MaRunWaitMapper mapper, MaPauseJson pauseJson) {
        this.mapper = mapper;
        this.pauseJson = pauseJson;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<MaPauseSnapshot> findWaiting(long sessionId) {
        MaRunWaitEntity entity = mapper.selectOne(new LambdaQueryWrapper<MaRunWaitEntity>()
                .eq(MaRunWaitEntity::getSessionId, sessionId)
                .eq(MaRunWaitEntity::getState, MaRunWaitState.WAITING.name())
                .orderByDesc(MaRunWaitEntity::getId)
                .last("LIMIT 1"));
        return Optional.ofNullable(entity).map(this::toSnapshot);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void save(MaPauseSnapshot state) {
        if (state == null || state.getSessionId() == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        // 旧等待先收口：同一会话只允许一条可续跑状态，否则下一句可能续到上一次的询问
        mapper.update(null, new LambdaUpdateWrapper<MaRunWaitEntity>()
                .eq(MaRunWaitEntity::getSessionId, state.getSessionId())
                .eq(MaRunWaitEntity::getState, MaRunWaitState.WAITING.name())
                .set(MaRunWaitEntity::getState, MaRunWaitState.DONE.name())
                .set(MaRunWaitEntity::getUpdateTime, now));
        mapper.insert(toEntity(state, now));
    }

    /** {@inheritDoc} */
    @Override
    public boolean markDone(long sessionId) {
        return close(sessionId, MaRunWaitState.DONE);
    }

    /** {@inheritDoc} */
    @Override
    public boolean markCancelled(long sessionId) {
        return close(sessionId, MaRunWaitState.CANCELLED);
    }

    /**
     * 把同会话所有 WAITING 行收成指定终态。
     *
     * @param sessionId 会话主键
     * @param state     DONE / CANCELLED
     * @return true 表示至少更新了一行
     */
    private boolean close(long sessionId, MaRunWaitState state) {
        int rows = mapper.update(null, new LambdaUpdateWrapper<MaRunWaitEntity>()
                .eq(MaRunWaitEntity::getSessionId, sessionId)
                .eq(MaRunWaitEntity::getState, MaRunWaitState.WAITING.name())
                .set(MaRunWaitEntity::getState, state.name())
                .set(MaRunWaitEntity::getUpdateTime, LocalDateTime.now()));
        return rows > 0;
    }

    /**
     * 实体 → 领域快照。
     *
     * <p>pausedMember 优先读完整 JSON；JSON 丢失时用拆列信息补一个最小成员，
     * 让「仍能定位是谁在问」优先于「整条无法恢复」。
     *
     * @param entity 行
     * @return 快照
     */
    private MaPauseSnapshot toSnapshot(MaRunWaitEntity entity) {
        MaPauseSnapshot state = new MaPauseSnapshot();
        state.setSessionId(entity.getSessionId());
        state.setMaDefId(entity.getMaDefId());
        state.setSchemeName(entity.getSchemeName());
        state.setTemplate(entity.getTemplate());
        state.setDepth(entity.getDepth() == null ? 0 : entity.getDepth());
        state.setParentKey(entity.getParentKey());
        state.setUserText(entity.getUserText());
        state.setModelUser(entity.getModelUser());
        MaPauseMember paused = pauseJson.readMember(entity.getPausedMemberJson());
        if (paused == null) {
            paused = new MaPauseMember();
            paused.setParticipantId(entity.getPausedParticipantId());
            paused.setParticipantName(entity.getPausedParticipantName());
            paused.setParticipantKind(entity.getPausedParticipantKind());
            paused.setParticipantDepth(entity.getPausedParticipantDepth() == null
                    ? 0 : entity.getPausedParticipantDepth());
            paused.setRouteKey(entity.getPausedRouteKey());
        }
        state.setPausedMember(paused);
        state.setPausedAnswer(entity.getPausedAnswer() == null
                ? paused.getAnswer() : entity.getPausedAnswer());
        AgentChoice choice = pauseJson.readChoice(entity.getOptionsJson());
        if (choice == null) {
            choice = new AgentChoice(entity.getQuestion(), java.util.List.of());
        }
        state.setChoice(choice);
        state.setDone(pauseJson.readMembers(entity.getDoneJson()));
        state.setRemaining(pauseJson.readMembers(entity.getRemainingJson()));
        state.setSkipped(pauseJson.readMembers(entity.getSkippedJson()));
        state.setPreWrittenKeys(pauseJson.readKeys(entity.getPreWrittenKeysJson()));
        state.setSeedDoneSize(entity.getSeedDoneSize() == null ? 0 : entity.getSeedDoneSize());
        state.setWrittenKeys(pauseJson.readKeys(entity.getWrittenKeysJson()));
        state.setGateway(pauseJson.readParticipant(entity.getGatewayJson()));
        state.setJudgeReasoning(entity.getJudgeReasoning());
        state.setDecisionBlock(entity.getDecisionBlock());
        return state;
    }

    /**
     * 领域快照 → 实体。
     *
     * @param state 快照
     * @param now   本次写入时间
     * @return 行
     */
    private MaRunWaitEntity toEntity(MaPauseSnapshot state, LocalDateTime now) {
        MaPauseMember paused = state.getPausedMember();
        MaRunWaitEntity entity = new MaRunWaitEntity();
        entity.setSessionId(state.getSessionId());
        entity.setMaDefId(state.getMaDefId());
        entity.setSchemeName(state.getSchemeName());
        entity.setTemplate(state.getTemplate());
        entity.setDepth(state.getDepth());
        entity.setParentKey(state.getParentKey());
        if (paused != null) {
            entity.setPausedParticipantId(paused.getParticipantId());
            entity.setPausedParticipantName(paused.getParticipantName());
            entity.setPausedParticipantKind(paused.getParticipantKind());
            entity.setPausedParticipantDepth(paused.getParticipantDepth());
            entity.setPausedRouteKey(paused.getRouteKey());
            entity.setPausedAnswer(state.getPausedAnswer() == null
                    ? paused.getAnswer() : state.getPausedAnswer());
        }
        entity.setPausedMemberJson(pauseJson.writeMember(paused));
        AgentChoice choice = state.getChoice();
        entity.setQuestion(choice == null ? "" : choice.question());
        entity.setOptionsJson(pauseJson.writeChoice(choice));
        entity.setReply(choice == null ? "" : choice.reply());
        entity.setDoneJson(pauseJson.writeMembers(state.getDone()));
        entity.setRemainingJson(pauseJson.writeMembers(state.getRemaining()));
        entity.setSkippedJson(pauseJson.writeMembers(state.getSkipped()));
        entity.setPreWrittenKeysJson(pauseJson.writeKeys(
                state.getPreWrittenKeys() == null ? new LinkedHashSet<>() : state.getPreWrittenKeys()));
        entity.setSeedDoneSize(state.getSeedDoneSize());
        entity.setWrittenKeysJson(pauseJson.writeKeys(
                state.getWrittenKeys() == null ? new LinkedHashSet<>() : state.getWrittenKeys()));
        entity.setUserText(state.getUserText());
        entity.setModelUser(state.getModelUser());
        entity.setGatewayJson(pauseJson.writeParticipant(state.getGateway()));
        entity.setJudgeReasoning(state.getJudgeReasoning());
        entity.setDecisionBlock(state.getDecisionBlock());
        entity.setState(MaRunWaitState.WAITING.name());
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        return entity;
    }

    /**
     * 等待行状态。只在本实现内使用，避免把库值散落成裸字符串。
     */
    private enum MaRunWaitState {
        /** 仍在等用户回复，可被下一句续跑 */
        WAITING,
        /** 已续跑成功或已被新的停等取代 */
        DONE,
        /** 用户停止 / 会话取消 */
        CANCELLED
    }
}
