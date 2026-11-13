package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.service.PlatformPromptPrefix;
import com.agenthub.prompt.application.PromptAssembler;
import com.agenthub.prompt.application.PromptContextAdapters;
import com.agenthub.prompt.domain.enums.PromptPurpose;
import com.agenthub.prompt.domain.model.PromptAssemblyResult;
import com.agenthub.prompt.domain.model.PromptContext;
import com.agenthub.prompt.domain.model.PromptLayout;
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
 * 除正文外还把 {@link PromptLayout} 一并交给调用方：下游 Composer 需要知道「受保护前缀多长、
 * 哪些段是锁定段」，才能保证二次截断不砍红线、出站前能校验锁定段在开放段之前。
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
     * @param assembler 提示词组装门面（L1 → 锁定段 → 开放段 / L3 → 一次性替换）
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
     * @return 交给模型的系统提示（纯正文）
     */
    public String build(AgentDef agent) {
        return build(agent, null);
    }

    /**
     * @param agent     Agent 定义；systemPrompt 只含用户业务段，可空
     * @param userQuery 本轮用户原文，供 {{user_query}}；可空
     * @return 交给模型的系统提示（纯正文）
     */
    public String build(AgentDef agent, String userQuery) {
        return assemble(agent, userQuery).text();
    }

    /**
     * 方案根的装配：方案没有 Agent 的业务提示词，只带平台 ROUTER 前缀与 {{agent_name}}。
     *
     * <p>用途：竞争选优 / 投票 / 汇总这些「编排根自己出一次 LLM」的提示词。
     * 与旧虚拟根壳等价（壳里 systemPrompt / 模板引用都为空），但不创建任何 Agent 对象。
     *
     * @param rootName  方案展示名，供 {{agent_name}}；可空
     * @param userQuery 本轮用户原文；可空
     * @return 交给模型的系统提示（纯正文）
     */
    public String buildRoot(String rootName, String userQuery) {
        return assemble(routerContext(rootName, null, userQuery)).text();
    }

    /**
     * 带结构描述的装配：正文 + layout（受保护前缀长度与各段正文）。
     * 试跑链路必须走这里，否则下游拿不到「锁定段在哪」，二次截断就可能砍到红线。
     *
     * @param agent     Agent 定义
     * @param userQuery 本轮用户原文；可空
     * @return 正文与结构描述
     */
    public Assembled assemble(AgentDef agent, String userQuery) {
        PromptContext context = adapters.fromChat(agent, userQuery, Map.of());
        // ROUTER 需要平台路由说明；它是平台侧文案，位置紧跟 L1，Agent 不可编辑
        String clause = prefix == null ? "" : prefix.routerClause();
        if (!clause.isBlank()) {
            PromptPurpose purpose = PromptContextAdapters.purposeOf(agent.getRunMode());
            context.setConstraintExtra(PromptContextAdapters.clauseFor(purpose, clause));
        }
        return assemble(context);
    }

    /**
     * Router 判路由专用装配：模板 / 追加要求来自默认接待方案页（{@code ma_def.config_json}）。
     *
     * <p>与 {@link #assemble(AgentDef, String)} 的区别只在「引用哪份 L3」：
     * ROUTER 是网关能力，不是某个专家的 CHAT 人设，因此不读 {@code agent_def} 的 CHAT 模板列，
     * 而是读页面可配的 ROUTER 模板引用与追加区。未选模板时仍按 ROUTER 用途取当前生效版。
     *
     * <p>配置的模板若在保存后被取消发布 / 删除，这里回退到 ROUTER 用途生效版并记 warn，
     * 避免整条工作台入口因一次模板下架而不可用；页面侧会回显「引用已失效」让管理员修正。
     *
     * @param agent       旧 Agent 试跑的编排根；方案试跑传 null
     * @param routerConfig 方案运行配置（含 ROUTER 模板引用 / 追加要求）；可空
     * @param userQuery   本轮用户原文；可空
     * @return 正文与结构描述
     */
    public Assembled assembleRouter(AgentDef agent, AgentRuntimeConfig routerConfig, String userQuery) {
        return assembleRouter(agent == null ? null : agent.getName(), routerConfig, userQuery);
    }

    /**
     * Router 判路由装配（方案根版本）：方案就是根，根名即方案展示名，不再需要 Agent 对象。
     *
     * @param rootName     方案展示名，供 {{agent_name}}；可空
     * @param routerConfig 方案运行配置（含 ROUTER 模板引用 / 追加要求）；可空
     * @param userQuery    本轮用户原文；可空
     * @return 正文与结构描述
     */
    public Assembled assembleRouter(String rootName, AgentRuntimeConfig routerConfig, String userQuery) {
        PromptContext context = routerContext(rootName, routerConfig, userQuery);
        try {
            return assemble(context);
        } catch (RuntimeException ex) {
            if (context.getTemplateId() == null) {
                throw ex;
            }
            // 只对「钉死模板失效」这类配置问题降级；L1 缺失等平台级阻断仍原样抛出
            log.warn("Router 提示词模板不可用，回退到 ROUTER 用途生效版：{}", ex.getMessage());
            return assemble(context.toBuilder().templateId(null).templateVersion(null).build());
        }
    }

    /**
     * 构造 Router 判路由的 PromptContext。
     *
     * @param rootName     编排根名（方案名 / 旧 Agent 名）；可空
     * @param routerConfig 方案运行配置；可空
     * @param userQuery    用户原文；可空
     * @return 统一上下文
     */
    private PromptContext routerContext(String rootName, AgentRuntimeConfig routerConfig, String userQuery) {
        PromptContext.PromptContextBuilder builder = PromptContext.builder()
                .purpose(PromptPurpose.ROUTER.name())
                .locale(null)
                .promptExtra(routerConfig == null ? null : routerConfig.getMaJudgePromptExtra())
                .agentName(rootName)
                .userQuery(userQuery)
                .variables(Map.of());
        if (routerConfig != null) {
            builder.templateId(routerConfig.getMaJudgePromptTemplateId())
                    .templateVersion(routerConfig.getMaJudgePromptTemplateVersion())
                    // 与专家配提示词同一套 L3：只覆盖 soft 段 + append-only 追加，不整段替换 L2
                    .overrideJson(routerConfig.getMaJudgePromptOverrideJson());
        }
        PromptContext context = builder.build();
        // 平台侧路由说明只属于 ROUTER：仍由 runtime 取出后放进 Context，prompt 域不反向依赖 agent
        String clause = prefix == null ? "" : prefix.routerClause();
        context.setConstraintExtra(PromptContextAdapters.clauseFor(PromptPurpose.ROUTER, clause));
        return context;
    }

    /**
     * 统一装配入口：只认 PromptContext，CHAT 与 ROUTER 共享同一条组装链路。
     *
     * @param context 统一上下文
     * @return 正文与结构描述
     */
    private Assembled assemble(PromptContext context) {
        PromptAssemblyResult result = assembler.assemble(context);
        // 只记版本与长度：正文含业务描述与用户输入，不进日志
        PromptLayout layout = result.getLayout() == null ? PromptLayout.EMPTY : result.getLayout();
        log.info("系统提示已组装 purpose={} l1v={} tpl={} v={} headChars={} chars={}",
                result.getPurpose(), result.getConstraintVersion(), result.getTemplateKey(),
                result.getTemplateVersion(), layout.headChars(), result.getText().length());
        return new Assembled(result.getText(), layout);
    }

    /**
     * 组装结果。所属域：runtime。
     *
     * @param text   系统提示正文
     * @param layout 结构描述；无结构信息时为 {@link PromptLayout#EMPTY}
     */
    public record Assembled(String text, PromptLayout layout) {
    }
}
