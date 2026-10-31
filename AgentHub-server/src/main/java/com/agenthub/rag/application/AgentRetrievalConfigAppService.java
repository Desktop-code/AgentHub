package com.agenthub.rag.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentModelBind;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.AgentModelBindRepository;
import com.agenthub.agent.domain.service.PurposeSlotRules;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.rag.api.dto.AgentRerankResp;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Agent 检索链路重排配置用例。所属域：rag。
 * <p>
 * 为什么写在 rag 而不是 agent：重排属于「检索后处理」，配置入口只在 Agent 详情「知识库」Tab，
 * 与 topK / RRF / 改写开关同属一条链路。但<strong>存储仍是
 * {@code agent_model_bind(purpose=RERANK)}</strong>——保持与模型槽、L3 挂载点同一份真相，
 * 不再另建 JSON 字段造成双写。
 * <p>
 * 约束：
 * <ul>
 *   <li>一个 Agent 最多一条 RERANK 绑定（{@link PurposeSlotRules#RERANK_MAX}），配新模型即替换；</li>
 *   <li>实例必须启用且用途为 RERANK，否则拒绝（禁止拿聊天模型顶替）；</li>
 *   <li>清空即逻辑删该行，不做物理删。</li>
 * </ul>
 */
@Service
public class AgentRetrievalConfigAppService {

    private final AgentDefRepository agents;
    private final AgentModelBindRepository binds;
    private final ModelInstanceRepository instances;
    private final RagModelLookup models;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;

    /**
     * @param agents          Agent 定义（确认 Agent 存在）
     * @param binds           模型绑定（唯一真相所在表）
     * @param instances       模型实例只读（回显展示名）
     * @param models          跨域只读引用，复用 EMBEDDING / RERANK 用途校验
     * @param operatorContext 操作人
     * @param operLogWriter   操作日志
     */
    public AgentRetrievalConfigAppService(AgentDefRepository agents, AgentModelBindRepository binds,
                                          ModelInstanceRepository instances, RagModelLookup models,
                                          OperatorContext operatorContext, OperLogWriter operLogWriter) {
        this.agents = agents;
        this.binds = binds;
        this.instances = instances;
        this.models = models;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
    }

    /**
     * 读该 Agent 当前生效的重排配置。
     *
     * @param agentId Agent 主键
     * @return 重排配置；未配置时各字段为空对象（不返回 null，前端可直接绑定）
     * @throws BizException Agent 不存在
     */
    public AgentRerankResp read(Long agentId) {
        requireAgent(agentId);
        return toResp(findRerankBind(agentId).orElse(null));
    }

    /**
     * 设置或清空重排模型。
     * <p>
     * 幂等：重复提交同一实例只做启用与优先级修正，不产生新行。
     *
     * @param agentId         Agent 主键
     * @param modelInstanceId 目标实例；null 或 0 表示关闭重排
     * @return 保存后的配置
     * @throws BizException Agent 不存在、实例不存在/停用/用途不符
     */
    @Transactional
    public AgentRerankResp save(Long agentId, Long modelInstanceId) {
        requireAgent(agentId);
        Long operatorId = operatorContext.requireUserId();
        Optional<AgentModelBind> existing = findRerankBind(agentId);

        // 清空分支：没有历史行就直接返回空，保证重复调用不报错
        if (modelInstanceId == null || modelInstanceId <= 0) {
            existing.ifPresent(bind -> {
                binds.logicalDelete(bind.getId(), operatorId);
                operLogWriter.write("rag", "rerank-off", "agent_model_bind", String.valueOf(bind.getId()),
                        "Agent 关闭检索重排 agentId=" + agentId);
            });
            return toResp(null);
        }

        ModelInstance target = models.requireRerank(modelInstanceId);
        // 同一实例已绑在别的用途上时不能复用：绑定表按 (agent, instance) 唯一
        for (AgentModelBind bind : binds.listByAgentId(agentId)) {
            if (existing.map(AgentModelBind::getId).filter(id -> id.equals(bind.getId())).isPresent()) {
                continue;
            }
            if (bind.getModelInstanceId().equals(modelInstanceId)) {
                throw new BizException(ErrorCode.DUPLICATE, "该模型已在此 Agent 的其它用途槽中绑定，请先移除");
            }
        }

        if (existing.isPresent()) {
            AgentModelBind bind = existing.get();
            if (bind.getModelInstanceId().equals(modelInstanceId)) {
                // 同实例：只把启停与优先级纠回正常，避免「关着重排却以为已配置」的半截状态
                bind.setPurpose(ModelCapability.RERANK.name());
                bind.setStatus(AgentStatus.ENABLED);
                // RERANK 单槽，不存在降级链；统一写 10 便于排序稳定
                bind.setPriority(10);
                binds.update(bind, operatorId);
            } else {
                // 换模型：先删旧行再插新行，绑定表不支持改 model_instance_id
                binds.logicalDelete(bind.getId(), operatorId);
                insertRerank(agentId, target, operatorId);
            }
        } else {
            insertRerank(agentId, target, operatorId);
        }
        operLogWriter.write("rag", "rerank-on", "agent_model_bind", String.valueOf(modelInstanceId),
                "Agent 设置检索重排 agentId=" + agentId + " model=" + target.getDisplayName());
        return toResp(findRerankBind(agentId).orElse(null));
    }

    /**
     * 插入一条 RERANK 绑定。优先级固定 10：RERANK 槽只允许一条，无需降级链。
     *
     * @param agentId    Agent 主键
     * @param instance   已校验的重排实例
     * @param operatorId 操作人
     */
    private void insertRerank(Long agentId, ModelInstance instance, Long operatorId) {
        AgentModelBind bind = new AgentModelBind();
        bind.setAgentId(agentId);
        bind.setModelInstanceId(instance.getId());
        bind.setPurpose(ModelCapability.RERANK.name());
        bind.setPriority(10);
        bind.setStatus(AgentStatus.ENABLED);
        binds.insert(bind, operatorId);
    }

    /**
     * 取该 Agent 的 RERANK 绑定行（未删范围内第一条）。
     * 用途按「purpose 覆盖优先、否则跟实例 capability」解析，避免历史空 purpose 行漏判。
     *
     * @param agentId Agent 主键
     * @return 绑定；未配置为空
     */
    private Optional<AgentModelBind> findRerankBind(Long agentId) {
        for (AgentModelBind bind : binds.listByAgentId(agentId)) {
            Optional<ModelInstance> instance = instances.findById(bind.getModelInstanceId());
            Optional<ModelCapability> purpose = PurposeSlotRules.effectivePurpose(bind, instance.orElse(null));
            if (purpose.isPresent() && purpose.get() == ModelCapability.RERANK) {
                return Optional.of(bind);
            }
        }
        return Optional.empty();
    }

    private AgentRerankResp toResp(AgentModelBind bind) {
        AgentRerankResp resp = new AgentRerankResp();
        if (bind == null) {
            return resp;
        }
        resp.setBindId(bind.getId());
        resp.setModelInstanceId(bind.getModelInstanceId());
        instances.findById(bind.getModelInstanceId()).ifPresent(instance -> {
            resp.setDisplayName(instance.getDisplayName());
            resp.setModelId(instance.getModelId());
        });
        return resp;
    }

    private void requireAgent(Long agentId) {
        if (agentId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Agent 不能为空");
        }
        AgentDef agent = agents.findById(agentId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "Agent 不存在"));
        if (agent.getId() == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Agent 不存在");
        }
    }
}
