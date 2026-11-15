package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.AgentChoice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 结构化选项的后端兜底：模型没调 {@code agenthub_choice} 时，把终答再整理成同一份选项。所属域：runtime 的 domain。
 *
 * <p>为什么要这一步（而不是前端猜正文列表）：
 * <ul>
 *   <li>自然语言写法无穷多（编号、短横线、夹理由、夹追问、先判断标准后候选），
 *       任何正则在真实模型上都会一轮一轮对不上；</li>
 *   <li>整理是一次<b>受控出站</b>：指令固定、输出有边界、结果可校验，失败就是「不出」，
 *       不会给出半组错按钮；</li>
 *   <li>责任收回到后端：前端只认落库的 CHOICE 事件，前后端不再各维护一套解析规则。</li>
 * </ul>
 *
 * <p>边界（任何一条不满足都直接返回 null，不产生出站）：
 * <ul>
 *   <li>终答为空或过短（不构成一次「请用户拍板」）；</li>
 *   <li>拿不到叶子专家的对话模型实例；</li>
 *   <li>整理模型失败 / 超时 / 输出不是合法结构。</li>
 * </ul>
 *
 * <p>失败只记日志，绝不向上抛：回答已经完成，不能因为「没有可选按钮」把这一轮判成失败。
 * 线程安全：无状态；依赖的 structurer 是单例只读使用。
 */
@Component
public class WorkbenchChoiceCollector {

    private static final Logger log = LoggerFactory.getLogger(WorkbenchChoiceCollector.class);

    /** 送进整理模型的终答上限：候选几乎都在正文前段，过长既浪费 token 也容易让模型跑题 */
    private static final int SOURCE_LIMIT = 6000;

    /** 终答短于这个长度时不可能承载 2~6 个候选，直接跳过（省一次出站） */
    private static final int MIN_SOURCE_CHARS = 16;

    private final ChoiceStructurer structurer;

    /**
     * @param structurer 小模型出站；允许为 null（单测 / 未装配出站能力时按「不整理」处理）
     */
    public WorkbenchChoiceCollector(ChoiceStructurer structurer) {
        this.structurer = structurer;
    }

    /**
     * 尝试把终答整理成选项。
     *
     * @param answer          助手终答正文，可空
     * @param agentId         叶子专家 id（计量归属）
     * @param modelInstanceId 叶子专家的对话模型实例；为空表示没有可用出站，直接放弃
     * @param userId          平台用户
     * @param sessionId       会话主键
     * @return 合法选项；任何一步不成立都返回 null
     */
    public AgentChoice collect(String answer, Long agentId, Long modelInstanceId, long userId, Long sessionId) {
        String text = answer == null ? "" : answer.trim();
        if (structurer == null || agentId == null || modelInstanceId == null || text.length() < MIN_SOURCE_CHARS) {
            return null;
        }
        String source = text.length() > SOURCE_LIMIT ? text.substring(0, SOURCE_LIMIT) : text;
        try {
            String raw = structurer.complete(agentId, sessionId, userId, modelInstanceId,
                    ChoiceStructurePrompt.text(), source);
            AgentChoice choice = WorkbenchChoiceJson.parseJson(raw).orElse(null);
            if (choice == null) {
                log.info("选项整理未得到合法结构 sessionId={} agentId={} rawChars={}",
                        sessionId, agentId, raw == null ? 0 : raw.length());
            }
            return choice;
        } catch (RuntimeException ex) {
            // 整理是锦上添花：超时 / 上游失败都只让本轮「没有 chips」，不改这一轮的成败
            log.warn("选项整理失败 sessionId={} agentId={} type={} msg={}",
                    sessionId, agentId, ex.getClass().getSimpleName(), ex.getMessage());
            return null;
        }
    }
}
