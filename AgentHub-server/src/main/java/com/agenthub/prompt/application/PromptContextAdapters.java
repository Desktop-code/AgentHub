package com.agenthub.prompt.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentModelBind;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.prompt.domain.enums.PromptPurpose;
import com.agenthub.prompt.domain.model.PromptContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * CHAT / 非 CHAT 的 Context 适配器。所属域：prompt。
 * <p>
 * 库表挂载是分叉的（CHAT 在 {@code agent_def}，非 CHAT 在各用途的 {@code agent_model_bind}），
 * 但拼装只有一套：本类只负责把两种来源读成同一个 {@link PromptContext}，
 * 之后一律交给 {@link PromptAssembler}。禁止在下游再写第二份拼接逻辑。
 * <p>
 * 用途映射（与模型槽、{@link PromptPurpose} 对齐）：
 * <ul>
 *   <li>REACT / PLAN_AND_EXECUTE → CHAT（计划模式的系统提示就是主对话提示）</li>
 *   <li>ROUTER → ROUTER</li>
 *   <li>用途槽绑定按绑定行上的 purpose 走；为空时按实例能力推断，本类不猜，直接取 CHAT</li>
 * </ul>
 * 线程安全：无状态单例。
 */
@Component
public class PromptContextAdapters {

    /**
     * 主对话：模板挂载在 {@code agent_def}。
     *
     * @param agent      Agent 定义，非空
     * @param userQuery  本轮用户原文，供 {{user_query}}；可空
     * @param variables  额外变量（如测试固定 current_time）；可空
     * @return 统一上下文
     */
    public PromptContext fromChat(AgentDef agent, String userQuery, Map<String, String> variables) {
        PromptPurpose purpose = purposeOf(agent.getRunMode());
        return PromptContext.builder()
                .purpose(purpose.name())
                .locale(null)
                .templateId(agent.getPromptTemplateId())
                .templateVersion(agent.getTemplateVersion())
                .overrideJson(agent.getPromptOverrideJson())
                .promptExtra(agent.getPromptExtra())
                // 历史用户业务段：L2 之后以「业务提示词」标记追加，老配置不丢
                .businessSegment(agent.getSystemPrompt())
                .agentName(agent.getName())
                .userQuery(userQuery)
                .variables(variables == null ? Map.of() : variables)
                .build();
    }

    /**
     * 非 CHAT 用途：模板挂载在用途槽的 {@code agent_model_bind}。
     *
     * @param bind       模型绑定行（其 purpose 即 L2 用途），非空
     * @param agentName  Agent 名称，供 {{agent_name}}；可空
     * @param userQuery  本轮用户原文；可空
     * @param variables  额外变量；可空
     * @return 统一上下文；绑定行 purpose 为空时按 CHAT（与「空则跟实例」的既有口径一致）
     */
    public PromptContext fromModelBind(AgentModelBind bind, String agentName, String userQuery,
                                       Map<String, String> variables) {
        PromptPurpose purpose = PromptPurpose.tryParse(bind.getPurpose()).orElse(PromptPurpose.CHAT);
        return PromptContext.builder()
                .purpose(purpose.name())
                .templateId(bind.getPromptTemplateId())
                .templateVersion(bind.getTemplateVersion())
                .overrideJson(bind.getPromptOverrideJson())
                .promptExtra(bind.getPromptExtra())
                .agentName(agentName)
                .userQuery(userQuery)
                .variables(variables == null ? Map.of() : variables)
                .build();
    }

    /**
     * 运行模式 → L2 用途。计划执行与 ReAct 共用主对话提示，不另建 PLAN 用途，
     * 避免同一份业务语义要维护两套模板。
     *
     * @param runMode REACT / ROUTER / PLAN_AND_EXECUTE；未知按 CHAT
     * @return 用途
     */
    public static PromptPurpose purposeOf(String runMode) {
        if (AgentRunMode.ROUTER.name().equals(runMode)) {
            return PromptPurpose.ROUTER;
        }
        return PromptPurpose.CHAT;
    }

    /**
     * 平台侧追加句：只有 ROUTER 需要，其它模式为空。
     * 由 runtime 的 {@code SystemPromptAssembler} 在填 Context 时调用并写入
     * {@code PromptContext#constraintExtra}——依赖方向保持 runtime → prompt，
     * 不让 prompt 回头依赖 agent 域的平台前缀类。
     *
     * @param purpose 用途
     * @param platformClause 平台侧说明（已由调用方从 PlatformPromptPrefix 取出），可空
     * @return 需要放进 Context 的说明；非 ROUTER 恒为空串
     */
    public static String clauseFor(PromptPurpose purpose, String platformClause) {
        if (purpose != PromptPurpose.ROUTER || platformClause == null) {
            return "";
        }
        return platformClause.trim();
    }
}
