package com.agenthub.runtime.domain.model;

import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.agent.domain.model.MaDef;

import java.util.List;

/**
 * 一次编排试跑的执行来源（ma_def 方案）。所属域：runtime。
 *
 * <p>MA-P2 刀2 起，Router 执行器的「模板 / 上下文策略 / 候选池 / 判路由模型」以本对象为准：
 * <ul>
 *   <li>虚拟根（{@code rootAgentId == null}）：没有 Agent 可读，只能用方案；</li>
 *   <li>绑根：仍然走 Agent 试跑入口，但开跑时按「该 ROUTER 当前启用的方案」装配；</li>
 *   <li>没有绑定方案时才回落旧 Agent 配置，并在时间线给出可见提醒。</li>
 * </ul>
 *
 * <p>本对象只在应用层与执行器之间传递，不落库；候选已按启用状态过滤，读取时不再查方案表，
 * 因此同一轮里「改方案候选」只影响下一轮试跑，不会让一轮执行中途换子。
 *
 * @param maId                  ma_def.id
 * @param code                  方案编码（时间线 / obs 展示）
 * @param name                  方案名称（虚拟根会话标题、obs 根步骤名）
 * @param template              五值模板之一；执行时覆盖 config 内的同名字段
 * @param runtime               已归一化的编排配置
 * @param candidates            启用中的候选，sort_no 小的在前
 * @param rootAgentId           绑定的 ROUTER；null＝虚拟根
 * @param judgeModelInstanceId  判路由 / 汇总模型实例；null 表示绑根回落 Agent 槽
 */
public record MaRunSpec(Long maId, String code, String name, String template,
                        AgentRuntimeConfig runtime, List<MaCandidate> candidates,
                        Long rootAgentId, Long judgeModelInstanceId) {

    /**
     * 从方案与候选构造。
     *
     * @param def        方案定义
     * @param candidates 启用中的候选（可空）
     * @param judgeModel 已解析的判路由模型实例；null 表示交给绑根兜底
     * @param runtime    已归一化的配置（模板已按 {@code def.template} 覆盖）
     * @return 执行来源
     */
    public static MaRunSpec of(MaDef def, List<MaCandidate> candidates, Long judgeModel,
                               AgentRuntimeConfig runtime) {
        return new MaRunSpec(def.getId(), def.getCode(), def.getName(), def.getTemplate(),
                runtime, candidates == null ? List.of() : List.copyOf(candidates),
                def.getRootAgentId(), judgeModel);
    }

    /**
     * @return true 表示虚拟根：没有可读的 Agent 模型槽 / 记忆策略，判路由模型必须来自方案
     */
    public boolean virtualRoot() {
        return rootAgentId == null;
    }

    /**
     * 时间线 / obs 用的方案标签：编码优先，缺失回退名称。
     *
     * @return 非空短标签
     */
    public String label() {
        if (code != null && !code.isBlank()) {
            return code.trim();
        }
        return name == null ? "" : name.trim();
    }
}
