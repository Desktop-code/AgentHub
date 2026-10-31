package com.agenthub.agent.application;

import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.AgentSkillBind;
import com.agenthub.agent.domain.model.AgentToolBind;
import com.agenthub.agent.domain.repository.AgentSkillBindRepository;
import com.agenthub.agent.domain.repository.AgentToolBindRepository;
import com.agenthub.agent.infra.PromptBudgetProperties;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.skill.domain.model.SkillDef;
import com.agenthub.skill.domain.repository.SkillDefRepository;
import com.agenthub.tool.domain.model.ToolItem;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 绑定保存时的提示词硬顶。所属域：agent。
 * 个数或估算字符超过平台上限则拒绝。ROUTER 父节点过多只警告，工具仍应放在子 REACT。
 */
@Component
public class AgentBindBudget {

    private final AgentToolBindRepository tools;
    private final AgentSkillBindRepository skills;
    private final AgentRefLookup refs;
    private final SkillDefRepository skillDefs;
    private final PromptBudgetProperties budgets;

    /**
     * @param tools    已有工具绑定
     * @param skills   已有 Skill 绑定
     * @param refs     取工具 schema
     * @param skillDefs Skill 正文。绑定列表用的查找会把正文清掉，估算必须读仓储
     * @param budgets  平台硬顶
     */
    public AgentBindBudget(AgentToolBindRepository tools, AgentSkillBindRepository skills, AgentRefLookup refs,
                           SkillDefRepository skillDefs, PromptBudgetProperties budgets) {
        this.tools = tools;
        this.skills = skills;
        this.refs = refs;
        this.skillDefs = skillDefs;
        this.budgets = budgets;
    }

    /**
     * @param runMode     REACT 或 ROUTER
     * @param agentId     Agent
     * @param adding      本次要启用的工具；停用时传 null
     * @param replacingId 更新时排除的旧绑定对应工具 id，新建为 null
     * @return ROUTER 过多时的警告；没有则为 null
     * @throws BizException 超过个数或 schema 硬顶
     */
    public String checkTool(String runMode, Long agentId, ToolItem adding, Long replacingId) {
        int count = 0;
        int chars = 0;
        List<AgentToolBind> rows = tools.listByAgentId(agentId);
        for (AgentToolBind bind : rows) {
            if (bind.getStatus() == null || bind.getStatus() != AgentStatus.ENABLED) {
                continue;
            }
            if (replacingId != null && replacingId.equals(bind.getToolItemId())) {
                continue;
            }
            count++;
            chars += schemaChars(refs.findTool(bind.getToolItemId()).orElse(null));
        }
        if (adding != null) {
            count++;
            chars += schemaChars(adding);
        }
        if (count > budgets.getMaxToolsPerAgent()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "工具绑定超过上限 " + budgets.getMaxToolsPerAgent());
        }
        if (chars > budgets.getToolsSchemaMaxCharsCap()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "工具 schema 字符超过平台硬顶");
        }
        if (AgentRunMode.ROUTER.name().equals(runMode) && count > budgets.getRouterWarnTools()) {
            return "ROUTER 上绑定的工具偏多，建议把工具放到子 REACT";
        }
        return null;
    }

    /**
     * @param runMode     REACT 或 ROUTER
     * @param agentId     Agent
     * @param adding      本次要启用的 Skill；停用时传 null
     * @param replacingId 更新时排除的 Skill id
     * @return ROUTER 过多时的警告
     * @throws BizException 超过个数或正文字符硬顶
     */
    public String checkSkill(String runMode, Long agentId, SkillDef adding, Long replacingId) {
        int count = 0;
        int chars = 0;
        for (AgentSkillBind bind : skills.listByAgentId(agentId)) {
            if (bind.getStatus() == null || bind.getStatus() != AgentStatus.ENABLED) {
                continue;
            }
            if (replacingId != null && replacingId.equals(bind.getSkillId())) {
                continue;
            }
            count++;
            chars += skillChars(skillDefs.findById(bind.getSkillId()).orElse(null));
        }
        if (adding != null) {
            count++;
            // requireSkill 会清掉正文，估算时按 id 再读一遍
            SkillDef full = skillDefs.findById(adding.getId()).orElse(adding);
            chars += skillChars(full);
        }
        if (count > budgets.getMaxSkillsPerAgent()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Skill 绑定超过上限 " + budgets.getMaxSkillsPerAgent());
        }
        if (chars > budgets.getSkillsInjectMaxCharsCap()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Skill 注入字符超过平台硬顶");
        }
        if (AgentRunMode.ROUTER.name().equals(runMode) && count > budgets.getRouterWarnSkills()) {
            return "ROUTER 上绑定的 Skill 偏多，建议把 Skill 放到子 REACT";
        }
        return null;
    }

    private int schemaChars(ToolItem item) {
        if (item == null || item.getInputSchemaJson() == null) {
            return 0;
        }
        return item.getInputSchemaJson().length();
    }

    private int skillChars(SkillDef skill) {
        if (skill == null || skill.getContentMd() == null) {
            return 0;
        }
        return skill.getContentMd().length();
    }
}
