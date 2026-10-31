package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.service.PlatformPromptPrefix;
import com.agenthub.prompt.application.PromptAssembler;
import com.agenthub.prompt.application.PromptContextAdapters;
import com.agenthub.prompt.domain.enums.PromptPurpose;
import com.agenthub.prompt.domain.model.PromptAssemblyResult;
import com.agenthub.prompt.domain.model.PromptContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 试跑路径的系统提示入口。所属域：runtime。
 * <p>
 * 从「自己拼前缀 + 业务段」改为**委托** {@link PromptAssembler}：
 * 顺序、变量替换、缺模板阻断全部只有一份实现，禁止在 runtime 再写第二套。
 * 本类只做两件事：把 AgentDef 填成 {@link PromptContext}，必要时补平台侧追加句。
 * <p>
 * 平台侧追加句（ROUTER 的「你是路由型助手…」）由本类从
 * {@link PlatformPromptPrefix#routerClause()} 取出后放进
 * {@code PromptContext#constraintExtra}：这样 prompt 域不必反向依赖 agent 域，
 * 依赖方向保持 runtime → 两者。
 * <p>
 * 失败语义：L1 未发布、用途未配置模板、缺必选段都会抛
 * {@link com.agenthub.common.error.BizException}（码 {@code PROMPT_UNAVAILABLE}），
 * 由试跑出口转成可见失败——绝不返回半截提示词。
 * <p>
 * 线程安全：无状态。
 */
@Component
public class SystemPromptAssembler {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptAssembler.class);

    private final PromptAssembler assembler;
    private final PromptContextAdapters adapters;
    /** ROUTER 平台追加句来源；单元测试可不注入，此时视为无追加句 */
    private final PlatformPromptPrefix prefix;

    /**
     * 生产构造器。
     *
     * @param assembler 提示词组装门面（L1 → L2 → L3 → 一次性替换）
     * @param adapters  AgentDef → PromptContext 的 CHAT 适配
     * @param prefix    平台前缀，用于取 ROUTER 追加句
     */
    @Autowired
    public SystemPromptAssembler(PromptAssembler assembler, PromptContextAdapters adapters,
                                 PlatformPromptPrefix prefix) {
        this.assembler = assembler;
        this.adapters = adapters;
        this.prefix = prefix;
    }

    /**
     * 不带平台追加句的构造器，供单元测试与配置预览使用。
     *
     * @param assembler 组装门面
     * @param adapters  Context 适配
     */
    public SystemPromptAssembler(PromptAssembler assembler, PromptContextAdapters adapters) {
        this(assembler, adapters, null);
    }

    /**
     * 不带用户输入的装配：配置预览或测试用，此时模板里 {{user_query}} 落空串。
     *
     * @param agent Agent 定义
     * @return 交给模型的系统提示
     */
    public String build(AgentDef agent) {
        return build(agent, null);
    }

    /**
     * @param agent     Agent 定义；systemPrompt 只含用户业务段，可空
     * @param userQuery 本轮用户原文，供 {{user_query}}；可空
     * @return 交给模型的系统提示
     */
    public String build(AgentDef agent, String userQuery) {
        PromptContext context = adapters.fromChat(agent, userQuery, Map.of());
        // ROUTER 需要平台路由说明；它是平台侧文案，位置紧跟 L1，Agent 不可编辑
        String clause = prefix == null ? "" : prefix.routerClause();
        if (!clause.isBlank()) {
            PromptPurpose purpose = PromptContextAdapters.purposeOf(agent.getRunMode());
            context.setConstraintExtra(PromptContextAdapters.clauseFor(purpose, clause));
        }
        PromptAssemblyResult result = assembler.assemble(context);
        // 只记版本与长度：正文含业务描述与用户输入，不进日志
        log.info("系统提示已组装 purpose={} l1v={} tpl={} v={} chars={}",
                result.getPurpose(), result.getConstraintVersion(), result.getTemplateKey(),
                result.getTemplateVersion(), result.getText().length());
        return result.getText();
    }
}
