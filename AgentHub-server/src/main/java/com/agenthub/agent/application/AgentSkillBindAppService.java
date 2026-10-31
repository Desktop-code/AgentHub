package com.agenthub.agent.application;

import com.agenthub.agent.api.dto.SkillBindCreateReq;
import com.agenthub.agent.api.dto.SkillBindResp;
import com.agenthub.agent.api.dto.SkillBindUpdateReq;
import com.agenthub.agent.domain.model.AgentSkillBind;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.repository.AgentSkillBindRepository;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.skill.domain.model.SkillDef;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Skill 绑定用例。所属域：agent。
 * 只保存 skill_def.id。响应和操作日志都不写正文。
 */
@Service
public class AgentSkillBindAppService {

    private final AgentLookup lookup;
    private final AgentSkillBindRepository binds;
    private final AgentRefLookup refs;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;
    private final AgentBindBudget budget;

    /**
     * @param lookup          确认 Agent 还在
     * @param binds           绑定仓储
     * @param refs            Skill 只读引用
     * @param operatorContext 操作人
     * @param operLogWriter   操作日志
     * @param budget          个数和正文字符硬顶
     */
    public AgentSkillBindAppService(AgentLookup lookup, AgentSkillBindRepository binds, AgentRefLookup refs,
                                    OperatorContext operatorContext, OperLogWriter operLogWriter, AgentBindBudget budget) {
        this.lookup = lookup;
        this.binds = binds;
        this.refs = refs;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
        this.budget = budget;
    }

    /**
     * @param agentId Agent 主键
     * @return 该 Agent 的 Skill 绑定
     */
    public List<SkillBindResp> list(Long agentId) {
        lookup.require(agentId);
        return binds.listByAgentId(agentId).stream().map(this::toResp).toList();
    }

    /**
     * @param agentId Agent 主键
     * @param req     Skill
     * @return 新绑定
     * @throws BizException Skill 不存在、未启用或已经绑过
     */
    @Transactional
    public SkillBindResp create(Long agentId, SkillBindCreateReq req) {
        var agent = lookup.require(agentId);
        SkillDef skill = refs.requireSkill(req.getSkillId(), true);
        if (binds.existsPair(agentId, skill.getId(), null)) {
            throw new BizException(ErrorCode.DUPLICATE, "该 Skill 已绑定");
        }
        int status = AgentChecks.initialStatus(req.getStatus());
        String warning = status == AgentStatus.ENABLED
                ? budget.checkSkill(agent.getRunMode(), agentId, skill, null)
                : null;
        AgentSkillBind bind = new AgentSkillBind();
        bind.setAgentId(agentId);
        bind.setSkillId(skill.getId());
        bind.setStatus(status);
        Long id = binds.insert(bind, operatorContext.requireUserId());
        operLogWriter.write("agent", "bind", "agent_skill_bind", String.valueOf(id), "绑定 Skill " + skill.getName());
        SkillBindResp resp = toResp(binds.findById(id).orElseThrow());
        resp.setWarning(warning);
        return resp;
    }

    /**
     * @param agentId Agent 主键
     * @param bindId  绑定主键
     * @param req     目标启停
     * @return 更新后的绑定
     */
    @Transactional
    public SkillBindResp update(Long agentId, Long bindId, SkillBindUpdateReq req) {
        var agent = lookup.require(agentId);
        AgentSkillBind existing = requireOwned(agentId, bindId);
        int status = AgentChecks.exactStatus(req.getStatus());
        SkillDef skill = refs.requireSkill(existing.getSkillId(), status == AgentStatus.ENABLED);
        String warning = status == AgentStatus.ENABLED
                ? budget.checkSkill(agent.getRunMode(), agentId, skill, existing.getSkillId())
                : null;
        existing.setStatus(status);
        binds.update(existing, operatorContext.requireUserId());
        operLogWriter.write("agent", "bind", "agent_skill_bind", String.valueOf(bindId), "更新 Skill 绑定 " + skill.getName());
        SkillBindResp resp = toResp(binds.findById(bindId).orElseThrow());
        resp.setWarning(warning);
        return resp;
    }

    /**
     * @param agentId Agent 主键
     * @param bindId  绑定主键
     */
    @Transactional
    public void remove(Long agentId, Long bindId) {
        lookup.require(agentId);
        AgentSkillBind existing = requireOwned(agentId, bindId);
        binds.logicalDelete(bindId, operatorContext.requireUserId());
        operLogWriter.write("agent", "unbind", "agent_skill_bind", String.valueOf(bindId),
                "移除 Skill 绑定 " + existing.getSkillId());
    }

    private AgentSkillBind requireOwned(Long agentId, Long bindId) {
        AgentSkillBind bind = binds.findById(bindId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "Skill 绑定不存在"));
        if (!agentId.equals(bind.getAgentId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "Skill 绑定不存在");
        }
        return bind;
    }

    private SkillBindResp toResp(AgentSkillBind bind) {
        SkillBindResp resp = new SkillBindResp();
        resp.setId(bind.getId());
        resp.setAgentId(bind.getAgentId());
        resp.setSkillId(bind.getSkillId());
        resp.setStatus(bind.getStatus());
        refs.findSkill(bind.getSkillId()).ifPresent(skill -> {
            resp.setName(skill.getName());
            resp.setTitle(skill.getTitle());
            resp.setTargetStatus(skill.getStatus());
        });
        return resp;
    }
}
