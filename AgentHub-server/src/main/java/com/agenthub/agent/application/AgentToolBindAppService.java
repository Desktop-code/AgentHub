package com.agenthub.agent.application;

import com.agenthub.agent.api.dto.ToolBindCreateReq;
import com.agenthub.agent.api.dto.ToolBindResp;
import com.agenthub.agent.api.dto.ToolBindUpdateReq;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.AgentToolBind;
import com.agenthub.agent.domain.repository.AgentToolBindRepository;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.tool.domain.model.ToolItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 工具绑定用例。所属域：agent。
 * 绑定时要求工具项已启用。日志和响应都不带工具源鉴权头。
 */
@Service
public class AgentToolBindAppService {

    private final AgentLookup lookup;
    private final AgentToolBindRepository binds;
    private final AgentRefLookup refs;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;
    private final AgentBindBudget budget;

    /**
     * @param lookup          确认 Agent 还在
     * @param binds           绑定仓储
     * @param refs            工具项只读引用
     * @param operatorContext 操作人
     * @param operLogWriter   操作日志
     * @param budget          个数和 schema 硬顶
     */
    public AgentToolBindAppService(AgentLookup lookup, AgentToolBindRepository binds, AgentRefLookup refs,
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
     * @return 该 Agent 的工具绑定
     */
    public List<ToolBindResp> list(Long agentId) {
        lookup.require(agentId);
        return binds.listByAgentId(agentId).stream().map(this::toResp).toList();
    }

    /**
     * @param agentId Agent 主键
     * @param req     工具项
     * @return 新绑定
     * @throws BizException 工具项不存在、未启用或已经绑过
     */
    @Transactional
    public ToolBindResp create(Long agentId, ToolBindCreateReq req) {
        var agent = lookup.require(agentId);
        ToolItem item = refs.requireTool(req.getToolItemId(), true);
        if (binds.existsPair(agentId, item.getId(), null)) {
            throw new BizException(ErrorCode.DUPLICATE, "该工具已绑定");
        }
        int status = AgentChecks.initialStatus(req.getStatus());
        String warning = status == AgentStatus.ENABLED
                ? budget.checkTool(agent.getRunMode(), agentId, item, null)
                : null;
        AgentToolBind bind = new AgentToolBind();
        bind.setAgentId(agentId);
        bind.setToolItemId(item.getId());
        bind.setStatus(status);
        Long id = binds.insert(bind, operatorContext.requireUserId());
        operLogWriter.write("agent", "bind", "agent_tool_bind", String.valueOf(id), "绑定工具 " + item.getName());
        ToolBindResp resp = toResp(binds.findById(id).orElseThrow());
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
    public ToolBindResp update(Long agentId, Long bindId, ToolBindUpdateReq req) {
        var agent = lookup.require(agentId);
        AgentToolBind existing = requireOwned(agentId, bindId);
        int status = AgentChecks.exactStatus(req.getStatus());
        ToolItem item = refs.requireTool(existing.getToolItemId(), status == AgentStatus.ENABLED);
        String warning = status == AgentStatus.ENABLED
                ? budget.checkTool(agent.getRunMode(), agentId, item, existing.getToolItemId())
                : null;
        existing.setStatus(status);
        binds.update(existing, operatorContext.requireUserId());
        operLogWriter.write("agent", "bind", "agent_tool_bind", String.valueOf(bindId), "更新工具绑定 " + item.getName());
        ToolBindResp resp = toResp(binds.findById(bindId).orElseThrow());
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
        AgentToolBind existing = requireOwned(agentId, bindId);
        binds.logicalDelete(bindId, operatorContext.requireUserId());
        operLogWriter.write("agent", "unbind", "agent_tool_bind", String.valueOf(bindId),
                "移除工具绑定 " + existing.getToolItemId());
    }

    private AgentToolBind requireOwned(Long agentId, Long bindId) {
        AgentToolBind bind = binds.findById(bindId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "工具绑定不存在"));
        if (!agentId.equals(bind.getAgentId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "工具绑定不存在");
        }
        return bind;
    }

    private ToolBindResp toResp(AgentToolBind bind) {
        ToolBindResp resp = new ToolBindResp();
        resp.setId(bind.getId());
        resp.setAgentId(bind.getAgentId());
        resp.setToolItemId(bind.getToolItemId());
        resp.setStatus(bind.getStatus());
        refs.findTool(bind.getToolItemId()).ifPresent(item -> {
            resp.setName(item.getName());
            resp.setTitle(item.getTitle());
            resp.setSourceName(refs.toolSourceName(item.getSourceId()));
            resp.setTargetStatus(item.getStatus());
        });
        return resp;
    }
}
