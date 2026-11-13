package com.agenthub.agent.application;

import com.agenthub.agent.api.dto.AgentCardResp;
import com.agenthub.agent.api.dto.AgentAvatarConfigDto;
import com.agenthub.agent.api.dto.AgentCopyReq;
import com.agenthub.agent.api.dto.AgentCreateReq;
import com.agenthub.agent.api.dto.AgentDetailResp;
import com.agenthub.agent.api.dto.AgentPageReq;
import com.agenthub.agent.api.dto.AgentRuntimeConfigReq;
import com.agenthub.agent.api.dto.AgentRuntimeConfigResp;
import com.agenthub.agent.api.dto.AgentStatusReq;
import com.agenthub.agent.api.dto.AgentUpdateReq;
import com.agenthub.agent.api.dto.PlatformPrefixResp;
import com.agenthub.agent.api.dto.PromptBindingReq;
import com.agenthub.agent.api.dto.PromptBindingResp;
import com.agenthub.agent.api.dto.RuntimeLimitsViewResp;
import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentAvatarConfig;
import com.agenthub.agent.domain.model.AgentPromptBinding;
import com.agenthub.agent.domain.model.AgentPromptTemplateOption;
import com.agenthub.agent.domain.model.AgentQuery;
import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.AgentModelBindRepository;
import com.agenthub.agent.domain.repository.AgentRouteRepository;
import com.agenthub.agent.domain.repository.AgentSkillBindRepository;
import com.agenthub.agent.domain.repository.AgentToolBindRepository;
import com.agenthub.agent.domain.service.AgentCodePolicy;
import com.agenthub.agent.domain.service.AgentCriticPolicy;
import com.agenthub.agent.domain.service.AgentPromptTemplatePort;
import com.agenthub.agent.domain.service.PlatformPromptPrefix;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.common.config.RuntimeLimitsProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Agent 定义用例。所属域：agent。
 * 只做配置读写。删除时连带逻辑删除绑定和路由，因为五张表没有物理外键。
 */
@Service
public class AgentAppService {

    private final AgentDefRepository agents;
    private final AgentModelBindRepository modelBinds;
    private final AgentToolBindRepository toolBinds;
    private final AgentSkillBindRepository skillBinds;
    private final AgentRouteRepository routes;
    private final AgentCodePolicy codes;
    private final AgentLookup lookup;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;
    private final PromptSafetyChecker safety;
    private final PlatformPromptPrefix prefixes;
    private final AgentLimitValidator limitValidator;
    private final RuntimeLimitsProperties limitProps;
    /** L3 提示词挂接端口；实现落在 prompt 域，agent 域只依赖接口 */
    private final AgentPromptTemplatePort promptTemplates;

    /** 默认绑定的语言；L2 管理端按用途+locale 只允许一条生效，新建统一钉 zh-CN。 */
    private static final String DEFAULT_PROMPT_LOCALE = "zh-CN";

    /**
     * @param agents          定义仓储
     * @param modelBinds      模型绑定，删除时一起清
     * @param toolBinds       工具绑定，删除时一起清
     * @param skillBinds      Skill 绑定，删除时一起清
     * @param routes          子路由，作为父或子都要清
     * @param codes           未删编码唯一
     * @param lookup          按 id 取未删除定义
     * @param operatorContext 操作人
     * @param operLogWriter   操作日志，摘要不含系统提示词和平台前缀正文
     * @param safety          用户段长度和危险模式
     * @param prefixes        详情里的只读前缀
     * @param limitValidator  运行限制 ≤ 平台硬顶
     * @param limitProps      平台默认与硬顶，给配置页
     * @param promptTemplates L3 模板读取 / 校验 / 预览
     */
    public AgentAppService(AgentDefRepository agents, AgentModelBindRepository modelBinds,
                           AgentToolBindRepository toolBinds, AgentSkillBindRepository skillBinds,
                           AgentRouteRepository routes, AgentCodePolicy codes, AgentLookup lookup,
                           OperatorContext operatorContext, OperLogWriter operLogWriter,
                           PromptSafetyChecker safety, PlatformPromptPrefix prefixes,
                           AgentLimitValidator limitValidator, RuntimeLimitsProperties limitProps,
                           AgentPromptTemplatePort promptTemplates) {
        this.agents = agents;
        this.modelBinds = modelBinds;
        this.toolBinds = toolBinds;
        this.skillBinds = skillBinds;
        this.routes = routes;
        this.codes = codes;
        this.lookup = lookup;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
        this.safety = safety;
        this.prefixes = prefixes;
        this.limitValidator = limitValidator;
        this.limitProps = limitProps;
        this.promptTemplates = promptTemplates;
    }

    /**
     * Agent 列表。
     *
     * <p>scope=SINGLE（Agent 管理页固定传）：只返回单兵，服务端排除 ROUTER 运行模式
     * 与多智能体方案绑根的主控；其它业务下拉不传 scope，仍可拿到 Router。
     *
     * @param req 分页和可选筛选
     * @return 列表页，不含系统提示词
     */
    public PageResult<AgentCardResp> page(AgentPageReq req) {
        AgentQuery query = new AgentQuery();
        query.setCurrent(req.getCurrent());
        query.setSize(req.getSize());
        query.setKeyword(req.getKeyword());
        query.setRunMode(AgentChecks.optionalRunMode(req.getRunMode()));
        query.setStatus(AgentChecks.optionalStatus(req.getStatus()));
        // 只有显式 SINGLE 才收窄口径，避免评测 / 计费等下拉拿不到 Router
        query.setSingleOnly(AgentChecks.singleScope(req.getScope()));
        PageResult<AgentDef> page = agents.page(query);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(this::toCard).toList());
    }

    /**
     * @param id 主键
     * @return 基本信息和绑定计数
     */
    public AgentDetailResp get(Long id) {
        return toDetail(lookup.require(id), List.of());
    }

    /**
     * 配置页展示用。三种模式都返回，切换单选时不必再查详情。
     * PLAN_AND_EXECUTE 与 REACT 共用同一份基础约束（不额外加路由句），因此复用 react 字段。
     *
     * @return 只读前缀
     */
    public PlatformPrefixResp platformPrefix() {
        PlatformPrefixResp resp = new PlatformPrefixResp();
        resp.setReact(prefixes.textFor(AgentRunMode.REACT.name()));
        resp.setRouter(prefixes.textFor(AgentRunMode.ROUTER.name()));
        resp.setPlan(prefixes.textFor(AgentRunMode.PLAN_AND_EXECUTE.name()));
        return resp;
    }

    /**
     * 配置页占位与前端超顶校验。只读，不落库。
     *
     * @return 各项 default / max
     */
    public RuntimeLimitsViewResp runtimeLimits() {
        RuntimeLimitsViewResp resp = new RuntimeLimitsViewResp();
        resp.setTimeoutMs(bound(limitProps.getTimeoutMs()));
        resp.setMaxRounds(bound(limitProps.getMaxRounds()));
        resp.setToolTimeoutMs(bound(limitProps.getToolTimeoutMs()));
        resp.setToolMaxRetries(bound(limitProps.getToolMaxRetries()));
        resp.setMaxToolCalls(bound(limitProps.getMaxToolCalls()));
        resp.setLlmTimeoutMs(bound(limitProps.getLlmTimeoutMs()));
        resp.setMaxLlmCalls(bound(limitProps.getMaxLlmCalls()));
        resp.setConsecutiveFailAbort(bound(limitProps.getConsecutiveFailAbort()));
        resp.setToolResultMaxChars(bound(limitProps.getToolResultMaxChars()));
        return resp;
    }

    /**
     * 未传运行模式时按 REACT。启停固定为正常，启停走单独接口。
     *
     * @param req 新 Agent
     * @return 详情
     */
    @Transactional
    public AgentDetailResp create(AgentCreateReq req) {
        AgentDef agent = new AgentDef();
        agent.setName(AgentChecks.requiredText(req.getName(), 64, "名称"));
        agent.setCode(AgentChecks.requiredText(req.getCode(), 64, "编码"));
        codes.assertUnique(agent.getCode(), null);
        // fillProfile 返回不可变列表；后续默认绑定 / 旧正文并入会追加告警，这里复制成可变集合
        List<String> warnings = new java.util.ArrayList<>(fillProfile(agent, req.getDescription(),
                req.getRunMode() == null ? AgentRunMode.REACT.name() : req.getRunMode(),
                req.getSystemPrompt(), req.getRemark()));
        // 形象/色板：整份 JSON 落库；空=清除为未配置
        agent.setAvatarConfigJson(toAvatarJson(req.getAvatarConfig()));
        // CHAT 的 L3 四列跟着 Agent 一起落库；未选模板时自动钉平台当前生效的对话模板
        applyPromptBinding(agent, req.getPrompt(), true, warnings);
        limitValidator.apply(agent, req.getTimeoutMs(), req.getMaxRounds(), req.getToolTimeoutMs(),
                req.getToolMaxRetries(), req.getMaxToolCalls(), req.getLlmTimeoutMs(),
                req.getMaxLlmCalls(), req.getConsecutiveFailAbort(), req.getToolResultMaxChars());
        agent.setRuntimeConfigJson(toRuntimeJson(req.getRuntimeConfig(), agent.getRunMode()));
        // 允许被编排调用：未传按模式默认，ROUTER 强制 0（编排根不可作子）
        agent.setAllowOrchestrated(AgentChecks.allowOrchestrated(req.getAllowOrchestrated(), agent.getRunMode()));
        agent.setStatus(AgentStatus.ENABLED);
        Long id = agents.insert(agent, operatorContext.requireUserId());
        operLogWriter.write("agent", "create", "agent_def", String.valueOf(id), "创建 Agent " + agent.getName());
        return toDetail(lookup.require(id), warnings);
    }

    /**
     * 切到 REACT 只改定义，不删除已有子路由。界面隐藏这些行，再切回 ROUTER 时仍在。
     *
     * @param id  主键
     * @param req 基本信息
     * @return 更新后的详情
     */
    @Transactional
    public AgentDetailResp update(Long id, AgentUpdateReq req) {
        AgentDef existing = lookup.require(id);
        String code = AgentChecks.requiredText(req.getCode(), 64, "编码");
        codes.assertUnique(code, id);
        existing.setName(AgentChecks.requiredText(req.getName(), 64, "名称"));
        existing.setCode(code);
        // fillProfile 返回不可变列表；默认绑定补钉会追加告警，这里复制成可变集合
        List<String> warnings = new java.util.ArrayList<>(fillProfile(existing, req.getDescription(), req.getRunMode(),
                req.getSystemPrompt(), req.getRemark()));
        // 每次保存都整体覆盖：空对象=清除未配置；非法键/色/URL 直接拒绝
        existing.setAvatarConfigJson(toAvatarJson(req.getAvatarConfig()));
        limitValidator.apply(existing, req.getTimeoutMs(), req.getMaxRounds(), req.getToolTimeoutMs(),
                req.getToolMaxRetries(), req.getMaxToolCalls(), req.getLlmTimeoutMs(),
                req.getMaxLlmCalls(), req.getConsecutiveFailAbort(), req.getToolResultMaxChars());
        existing.setRuntimeConfigJson(toRuntimeJson(req.getRuntimeConfig(), existing.getRunMode()));
        // 反向校验：已挂候选若用了本次要禁用的子运行模式，拒存；避免库里留下「校验区报错但已落库」的矛盾配置
        assertChildRunModesStillAllowed(id, existing.getRunMode(), existing.getRuntimeConfigJson());
        // 评审环角色闸：执行者恰好 1 个 + 评审者合法，保存即拦，避免「存得下、跑必挂」
        assertCriticRoles(id, existing.getRunMode(), existing.getRuntimeConfigJson());
        /*
         * 未传 = 本次不改：保留已落库值，避免其它「整份保存」的旁路接口漏传时把私有开关悄悄打开。
         * ROUTER 无论传什么都强制 0（allowOrchestratedOn 对 ROUTER 恒 false）。
         */
        Boolean allowReq = req.getAllowOrchestrated();
        existing.setAllowOrchestrated(allowReq == null
                ? (existing.allowOrchestratedOn() ? 1 : 0)
                : AgentChecks.allowOrchestrated(allowReq, existing.getRunMode()));
        // 未传 prompt 且已有绑定 = 本次不改；未传且仍未挂模板（存量）则保存时补钉默认，拒绝继续「无模板」
        applyPromptBinding(existing, req.getPrompt(), false, warnings);
        agents.updateProfile(existing, operatorContext.requireUserId());
        // 刀3 起本路径只写 agent_def；多智能体方案以 ma_def 为唯一真源，不再反向镜像配置。
        operLogWriter.write("agent", "update", "agent_def", String.valueOf(id), "更新 Agent " + existing.getName());
        return toDetail(existing, warnings);
    }

    /**
     * 复制 Agent：只复制定义本身（基本信息 / 运行限制 / 编排策略），
     * 以及 CHAT 的 L3 四列（模板、钉死版本、覆盖、追加）——复制品必须继承原绑定，
     * 不得在复制时打回默认模板，也不得打回「业务提示词」。
     * <p>
     * 不复制子路由 / 模型 / 工具 / Skill 绑定：那些是独立配置，复制出来会与源 Agent 争用同一批实例；
     * 需要时在复制品的详情页重新绑定。源 Agent 若本身是存量未挂模板（仅业务提示词），
     * 复制时按新建口径补钉平台默认模板，并把旧正文并入追加要求，避免复制品重新落回「无模板」。
     *
     * @param sourceId 源 Agent 主键
     * @param req      复制品的名称与编码（编码未删除范围内唯一）
     * @return 复制品详情
     * @throws BizException 源不存在、编码重复或平台无生效 CHAT 模板
     */
    @Transactional
    public AgentDetailResp copy(Long sourceId, AgentCopyReq req) {
        AgentDef source = lookup.require(sourceId);
        AgentDef agent = new AgentDef();
        agent.setName(AgentChecks.requiredText(req.getName(), 64, "名称"));
        agent.setCode(AgentChecks.requiredText(req.getCode(), 64, "编码"));
        codes.assertUnique(agent.getCode(), null);
        // 基本信息与运行限制原样继承；启停重置为正常，避免复制品直接带着停用态
        agent.setDescription(source.getDescription());
        agent.setAvatarConfigJson(source.getAvatarConfigJson());
        agent.setRunMode(source.getRunMode());
        agent.setSystemPrompt(source.getSystemPrompt());
        agent.setRemark(source.getRemark());
        agent.setTimeoutMs(source.getTimeoutMs());
        agent.setMaxRounds(source.getMaxRounds());
        agent.setToolTimeoutMs(source.getToolTimeoutMs());
        agent.setToolMaxRetries(source.getToolMaxRetries());
        agent.setMaxToolCalls(source.getMaxToolCalls());
        agent.setLlmTimeoutMs(source.getLlmTimeoutMs());
        agent.setMaxLlmCalls(source.getMaxLlmCalls());
        agent.setConsecutiveFailAbort(source.getConsecutiveFailAbort());
        agent.setToolResultMaxChars(source.getToolResultMaxChars());
        agent.setRuntimeConfigJson(source.getRuntimeConfigJson());
        agent.setAllowOrchestrated(source.allowOrchestratedOn() ? 1 : 0);
        agent.setStatus(AgentStatus.ENABLED);
        List<String> warnings = inheritPromptBinding(agent, source);
        Long id = agents.insert(agent, operatorContext.requireUserId());
        operLogWriter.write("agent", "copy", "agent_def", String.valueOf(id),
                "复制 Agent " + source.getName() + " → " + agent.getName());
        return toDetail(lookup.require(id), warnings);
    }

    /**
     * 复制时的 L3 继承：源已挂模板则逐列照搬（不做版本校验，避免源模板恰好不可解析时复制失败）；
     * 源未挂模板且非 ROUTER 时补钉平台默认，与新建口径一致。
     *
     * @param agent  复制品（systemPrompt 已从源带过来）
     * @param source 源定义
     * @return 本次复制产生的告警
     */
    private List<String> inheritPromptBinding(AgentDef agent, AgentDef source) {
        List<String> warnings = new java.util.ArrayList<>();
        // ROUTER 模式的提示词挂在 ROUTER 用途槽，agent_def 四列保持为空
        if (AgentRunMode.ROUTER.name().equals(source.getRunMode())) {
            clearPromptColumns(agent);
            return warnings;
        }
        if (source.getPromptTemplateId() != null) {
            String extra = mergeLegacyBusiness(agent, source.getPromptExtra(), warnings);
            agent.setPromptTemplateId(source.getPromptTemplateId());
            agent.setTemplateVersion(source.getTemplateVersion());
            agent.setPromptOverrideJson(source.getPromptOverrideJson());
            agent.setPromptExtra(extra);
            return warnings;
        }
        // 源是存量未挂模板：复制品补钉默认，旧正文并入追加要求（不带着「无模板」再复制一份）
        AgentPromptTemplateOption def = promptTemplates.defaultOption(
                AgentPromptTemplatePort.PURPOSE_CHAT, DEFAULT_PROMPT_LOCALE);
        if (def == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "平台还没有已发布生效的对话用途模板（CHAT），请先在「提示词」页发布后再复制 Agent");
        }
        String extra = mergeLegacyBusiness(agent, null, warnings);
        applyBinding(agent, promptTemplates.normalize(AgentPromptTemplatePort.PURPOSE_CHAT,
                def.getId(), def.getVersion(), null, extra));
        warnings.add("源 Agent 未挂模板，已为复制品绑定平台默认对话模板");
        return warnings;
    }

    /**
     * @param id  主键
     * @param req 目标状态
     * @return 更新后的详情
     */
    @Transactional
    public AgentDetailResp changeStatus(Long id, AgentStatusReq req) {
        AgentDef existing = lookup.require(id);
        int status = AgentChecks.exactStatus(req.getStatus());
        agents.updateStatus(id, status, operatorContext.requireUserId());
        String action = status == AgentStatus.ENABLED ? "enable" : "disable";
        operLogWriter.write("agent", action, "agent_def", String.valueOf(id), "更新 Agent 状态 " + existing.getName());
        return toDetail(lookup.require(id), List.of());
    }

    /**
     * 逻辑删除定义，并在同一事务里逻辑删除它的三类绑定，以及它作为父或子出现的路由。
     *
     * @param id 主键
     */
    @Transactional
    public void remove(Long id) {
        AgentDef existing = lookup.require(id);
        Long operatorId = operatorContext.requireUserId();
        modelBinds.logicalDeleteByAgentId(id, operatorId);
        toolBinds.logicalDeleteByAgentId(id, operatorId);
        skillBinds.logicalDeleteByAgentId(id, operatorId);
        routes.logicalDeleteByAgentId(id, operatorId);
        agents.logicalDelete(id, operatorId);
        operLogWriter.write("agent", "remove", "agent_def", String.valueOf(id), "删除 Agent " + existing.getName());
    }

    /**
     * 入库的只有用户段。超长和疑似密钥拒绝；覆盖系统、外连诱导默认放进 warnings。
     *
     * @return 本次保存的告警，可为空列表
     */
    private List<String> fillProfile(AgentDef agent, String description, String runMode, String systemPrompt, String remark) {
        agent.setDescription(AgentChecks.optionalText(description, 1000, "简介"));
        agent.setRunMode(AgentChecks.runMode(runMode));
        String stored = AgentChecks.prompt(systemPrompt);
        PromptSafetyChecker.PromptCheckResult result = safety.check(stored == null ? "" : stored);
        if (!result.ok()) {
            throw new BizException(ErrorCode.BAD_REQUEST, String.join("；", result.errors()));
        }
        agent.setSystemPrompt(stored);
        agent.setRemark(AgentChecks.optionalText(remark, 500, "备注"));
        return result.warnings();
    }

    /**
     * 把 L3 四列写进 AgentDef（CHAT 挂载点）。
     * <p>
     * 挂载口径严格按架构 §3.3：{@code agent_def} 只承载 **CHAT**；
     * ROUTER 判路由 / REWRITE / SUMMARY / MEMORY 一律挂到对应用途的 {@code agent_model_bind} 行，
     * 因此 ROUTER 模式 Agent 不在此处配置提示词（前端隐藏该区块，后端也拒绝）。
     * <p>
     * 为什么必须拒绝而不是「顺手存下来」：运行时 CHAT 适配器按 runMode 推导用途，
     * ROUTER 模式会以 ROUTER 用途去组装，而这里存的是 CHAT 模板，组装必然以「用途不匹配」阻断出站——
     * 配置页看着保存成功、试跑却跑不起来，比直接报错难查得多。
     * <p>
     * 另外：绑了模板后 system_prompt 不再当作「整段业务提示词」另写一份，
     * 而是由 Assembler 以「业务提示词」标注追加在 L2 之后、prompt_extra 之前，
     * 因此不会绕过 soft 段校验（不允许用 system_prompt 覆盖 core）。
     *
     * @param agent    正在保存的定义（runMode 已规范化）
     * @param req      前端提交的四列；为 null 表示本次未提交
     * @param creating true=新建（必须完成绑定）；false=更新（已有绑定且未提交则保留）
     * @param warnings 本次保存的告警，用于回告「自动绑定 / 旧正文已并入」
     * @throws BizException 段落键非法、core 段、版本不一致、用途不符、ROUTER 模式误配或平台无生效模板
     */
    private void applyPromptBinding(AgentDef agent, PromptBindingReq req, boolean creating, List<String> warnings) {
        // ROUTER 模式：agent_def 只承载 CHAT，保持既有拒绝语义；显式空值仅清列，不做默认绑定
        if (AgentRunMode.ROUTER.name().equals(agent.getRunMode())) {
            if (req != null) {
                assertChatMountable(agent.getRunMode(), req);
                clearPromptColumns(agent);
            }
            return;
        }
        // 更新且已有绑定：未提交 prompt 视为「只改其它字段」，保留原四列（不静默换版）
        if (!creating && req == null && agent.getPromptTemplateId() != null) {
            return;
        }
        // 显式选了模板：沿用既有硬闸（用途对齐、版本钉死、core 段拒绝）
        if (req != null && req.getTemplateId() != null) {
            assertChatMountable(agent.getRunMode(), req);
            String extra = mergeLegacyBusiness(agent, req.getPromptExtra(), warnings);
            applyBinding(agent, promptTemplates.normalize(AgentPromptTemplatePort.PURPOSE_CHAT,
                    req.getTemplateId(), req.getTemplateVersion(), req.getOverride(), extra));
            return;
        }
        // 未选模板（新建 / 存量补钉 / 显式清空）：钉平台该用途当前生效版本，禁止回到「未挂模板」
        AgentPromptTemplateOption def = promptTemplates.defaultOption(
                AgentPromptTemplatePort.PURPOSE_CHAT, DEFAULT_PROMPT_LOCALE);
        if (def == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "平台还没有已发布生效的对话用途模板（CHAT），请先在「提示词」页发布后再保存 Agent");
        }
        // 追加区：本次传了就用本次的；没传（更新）则保留库内已有值，避免只改名称时丢追加要求
        String requestedExtra = req == null ? agent.getPromptExtra() : req.getPromptExtra();
        String extra = mergeLegacyBusiness(agent, requestedExtra, warnings);
        // 换到默认模板后旧覆盖键对新版模板无意义，这里不带覆盖，交由配置者在弹窗里重填
        applyBinding(agent, promptTemplates.normalize(AgentPromptTemplatePort.PURPOSE_CHAT,
                def.getId(), def.getVersion(), null, extra));
        String label = def.getName() == null || def.getName().isBlank() ? def.getTemplateKey() : def.getName();
        warnings.add("已自动绑定平台默认对话模板「" + label + " v" + def.getVersion() + "」");
    }

    /**
     * 把四列写进定义。抽成方法是为了让「显式选择」与「默认绑定」两条路径落同样的值。
     *
     * @param agent   正在保存的定义
     * @param binding 已通过保存闸的绑定值
     */
    private static void applyBinding(AgentDef agent, AgentPromptBinding binding) {
        agent.setPromptTemplateId(binding.getTemplateId());
        agent.setTemplateVersion(binding.getTemplateVersion());
        agent.setPromptOverrideJson(binding.getOverrideJson());
        agent.setPromptExtra(binding.getPromptExtra());
    }

    /** 清空 CHAT 四列。仅 ROUTER 模式使用：该模式的提示词挂在 ROUTER 用途槽。 */
    private static void clearPromptColumns(AgentDef agent) {
        agent.setPromptTemplateId(null);
        agent.setTemplateVersion(null);
        agent.setPromptOverrideJson(null);
        agent.setPromptExtra(null);
    }

    /**
     * 存量「业务提示词」收口：绑上模板后不再与 L3 双轨维护整段正文。
     * <p>
     * 沿用现网前端的并入口径（追加要求在前、旧正文在后，空行分隔），并把
     * {@code system_prompt} 清空——Assembler 会以【业务提示词】标注再追加一次，
     * 若不清空就变成同一段正文出现两遍。
     * <p>
     * 长度超限直接拒绝，不静默截断：配置者需要知道合并后放不下。
     *
     * @param agent         正在保存的定义（含已落库或本轮提交的 systemPrompt）
     * @param requestedExtra 本次请求带来的追加要求；可空
     * @param warnings      告警收集器
     * @return 合并后的追加要求；无任何正文时返回原值（可能为 null）
     * @throws BizException 合并后超过追加区上限
     */
    private String mergeLegacyBusiness(AgentDef agent, String requestedExtra, List<String> warnings) {
        String legacy = agent.getSystemPrompt() == null ? "" : agent.getSystemPrompt().trim();
        String extra = requestedExtra == null ? "" : requestedExtra.trim();
        if (legacy.isEmpty()) {
            return extra.isEmpty() ? null : extra;
        }
        String merged = extra.isEmpty() ? legacy : extra + "\n\n" + legacy;
        if (merged.length() > AgentChecks.PROMPT_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "业务提示词并入追加要求后超过长度，最长 " + AgentChecks.PROMPT_MAX + "，请先精简正文");
        }
        // 已并入追加区：清空旧列，杜绝同一段正文在出站时被追加两遍
        agent.setSystemPrompt(null);
        warnings.add("原「系统提示词」正文已并入追加要求");
        return merged;
    }

    /**
     * agent_def 挂载点只接受 CHAT：ROUTER 模式的提示词请在「模型配置」的 ROUTER 槽里配。
     *
     * @param runMode 已规范化的运行模式
     * @param req     前端提交的四列
     * @throws BizException ROUTER 模式却提交了非空模板
     */
    private static void assertChatMountable(String runMode, PromptBindingReq req) {
        if (req.getTemplateId() == null) {
            return;
        }
        if (AgentRunMode.ROUTER.name().equals(runMode)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "Router 模式的提示词请在「模型配置」的 ROUTER 用途槽中配置，Agent 定义只承载 CHAT 模板");
        }
    }

    private AgentCardResp toCard(AgentDef agent) {
        AgentCardResp resp = new AgentCardResp();
        resp.setId(agent.getId());
        resp.setName(agent.getName());
        resp.setCode(agent.getCode());
        resp.setDescription(agent.getDescription());
        resp.setAvatarConfig(toAvatarResp(agent.getAvatarConfigJson()));
        resp.setRunMode(agent.getRunMode());
        resp.setAllowOrchestrated(agent.allowOrchestratedOn());
        resp.setStatus(agent.getStatus());
        resp.setUpdateTime(agent.getUpdateTime());
        return resp;
    }

    private AgentDetailResp toDetail(AgentDef agent, List<String> warnings) {
        AgentDetailResp resp = new AgentDetailResp();        resp.setId(agent.getId());
        resp.setName(agent.getName());
        resp.setCode(agent.getCode());
        resp.setDescription(agent.getDescription());
        resp.setAvatarConfig(toAvatarResp(agent.getAvatarConfigJson()));
        resp.setRunMode(agent.getRunMode());
        resp.setAllowOrchestrated(agent.allowOrchestratedOn());
        resp.setSystemPrompt(agent.getSystemPrompt());
        // L3 回显：用途按运行模式推导，与 applyPromptBinding 的校验口径同源
        resp.setPrompt(promptBindingResp(agent));
        resp.setPlatformPrefix(prefixes.textFor(agent.getRunMode()));
        resp.setWarnings(warnings == null ? List.of() : warnings);
        resp.setTimeoutMs(agent.getTimeoutMs());
        resp.setMaxRounds(agent.getMaxRounds());
        resp.setToolTimeoutMs(agent.getToolTimeoutMs());
        resp.setToolMaxRetries(agent.getToolMaxRetries());
        resp.setMaxToolCalls(agent.getMaxToolCalls());
        resp.setLlmTimeoutMs(agent.getLlmTimeoutMs());
        resp.setMaxLlmCalls(agent.getMaxLlmCalls());
        resp.setConsecutiveFailAbort(agent.getConsecutiveFailAbort());
        resp.setToolResultMaxChars(agent.getToolResultMaxChars());
        resp.setRuntimeConfig(toRuntimeResp(agent));
        resp.setStatus(agent.getStatus());
        resp.setRemark(agent.getRemark());
        resp.setModelBindCount(modelBinds.countByAgentId(agent.getId()));
        resp.setToolBindCount(toolBinds.countByAgentId(agent.getId()));
        resp.setSkillBindCount(skillBinds.countByAgentId(agent.getId()));
        resp.setRouteCount(routes.countByParentId(agent.getId()));
        resp.setCreateTime(agent.getCreateTime());
        resp.setUpdateTime(agent.getUpdateTime());
        return resp;
    }

    /**
     * agent_def 四列 → 详情回显。
     * <p>
     * agent_def 只承载 CHAT：ROUTER 模式的提示词挂在 ROUTER 用途槽的绑定行上，
     * 因此这里对 ROUTER 返回 {@code supported=false}，前端据此隐藏整个区块，
     * 避免出现「填了 CHAT 模板但运行时按 ROUTER 组装」的错配。
     *
     * @param agent Agent 定义
     * @return 回显对象；ROUTER 模式时 supported=false
     */
    private PromptBindingResp promptBindingResp(AgentDef agent) {
        String purpose = AgentRunMode.ROUTER.name().equals(agent.getRunMode())
                ? null
                : AgentPromptTemplatePort.PURPOSE_CHAT;
        return AgentPromptDtos.toResp(promptTemplates, purpose, agent.getPromptTemplateId(),
                agent.getTemplateVersion(), agent.getPromptOverrideJson(), agent.getPromptExtra());
    }

    /**
     * 请求可空；空则写入模式默认。
     * ROUTER：多意图开 + CONCAT + N=3；PLAN_AND_EXECUTE：自动执行 + 8 步 + 0 重试。
     * 两组字段一起收，规范化和夹紧由 {@link AgentRuntimeConfig#toStorageJson} 统一做。
     */
    /**
     * 请求体形象配置 → 落库 JSON。全空落 null，表示清除。
     *
     * @param dto 请求体；可空
     * @return JSON；未配置时 null
     * @throws BizException 预置键 / 色值 / 上传 URL 非法
     */
    private static String toAvatarJson(AgentAvatarConfigDto dto) {
        if (dto == null) {
            return null;
        }
        AgentAvatarConfig config = AgentAvatarConfig.of(
                dto.getType(), dto.getIcon(), dto.getColor(), dto.getUrl());
        return config == null ? null : config.toJson();
    }

    /**
     * 库内 JSON → 响应体。脏值按未配置处理，不阻断详情。
     *
     * @param json 库内原文
     * @return 响应体；未配置时 null
     */
    private static AgentAvatarConfigDto toAvatarResp(String json) {
        return AgentAvatarConfigDto.fromJson(json);
    }

    private static String toRuntimeJson(AgentRuntimeConfigReq req, String runMode) {
        AgentRuntimeConfig config = new AgentRuntimeConfig();
        if (req != null) {
            config.setMaTemplate(req.getMaTemplate());
            config.setMaContextPolicy(req.getMaContextPolicy());
            config.setMaAllowParentHistoryWhenSingle(req.getMaAllowParentHistoryWhenSingle());
            config.setMultiIntentEnabled(req.getMultiIntentEnabled());
            config.setAnswerMode(req.getAnswerMode());
            config.setMaxRouteChildren(req.getMaxRouteChildren());
            config.setRequirePlanConfirm(req.getRequirePlanConfirm());
            config.setMaxPlanSteps(req.getMaxPlanSteps());
            config.setMaxStepRetries(req.getMaxStepRetries());
            config.setMaChildRunModesAllowed(req.getMaChildRunModesAllowed());
            config.setMaCriticMaxRounds(req.getMaCriticMaxRounds());
            config.setMaCriticAgentId(req.getMaCriticAgentId());
            // 竞争选优策略：只落配置，是否生效由执行侧按模板判断
            config.setCompeteStrategy(req.getCompeteStrategy());
        }
        return AgentRuntimeConfig.toStorageJson(config, runMode);
    }

    /**
     * 保存编排策略前的反向校验：已挂候选若使用了即将被禁用的子运行模式，直接拒存。
     *
     * <p>为什么要在服务端拦：前端校验只是提示，把 allowed 缩回仅 ReAct 后若库里仍挂着 P&E 子，
     * 下一轮试跑会静默跳过该子，用户却以为它还参与编排。这里让保存失败并给出中文原因，
     * 由用户先移除/停用候选再关开关，状态永远自洽。
     *
     * @param parentAgentId 父 Agent 主键
     * @param parentRunMode 父运行模式（非 ROUTER 直接跳过）
     * @param runtimeJson   本次要落库的运行配置 JSON
     * @throws BizException 已有候选的运行模式不在新的允许集合内
     */
    private void assertChildRunModesStillAllowed(Long parentAgentId, String parentRunMode, String runtimeJson) {
        if (!AgentRunMode.ROUTER.name().equals(parentRunMode)) {
            return;
        }
        AgentRuntimeConfig next = AgentRuntimeConfig.parse(runtimeJson, parentRunMode);
        for (AgentRoute route : routes.listByParentId(parentAgentId)) {
            AgentDef child = lookup.find(route.getChildAgentId()).orElse(null);
            if (child == null || next.allowsChildRunMode(child.getRunMode())) {
                continue;
            }
            String mode = AgentRunMode.PLAN_AND_EXECUTE.name().equals(child.getRunMode()) ? "计划执行" : "ReAct";
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "已挂候选「" + child.getName() + "」使用" + mode
                            + "模式，请先移除或停用该候选，再关闭「允许的子运行模式」");
        }
    }

    /**
     * 评审环保存闸：模板为 CRITIC_LOOP 时，执行者候选必须恰好 1 个，且评审者合法、非自评。
     *
     * <p>与 {@link AgentCriticPolicy} 同口径：执行者按「启用路由行 + 子可用」统计，
     * 评审者从候选计数中排除（它挂不挂成候选都不影响执行者个数）。
     *
     * @param parentAgentId 父 Agent 主键
     * @param parentRunMode 父运行模式（非 ROUTER 直接跳过）
     * @param runtimeJson   本次要落库的运行配置 JSON
     * @throws BizException 执行者不是 1 个、未选评审者或评审者非法
     */
    private void assertCriticRoles(Long parentAgentId, String parentRunMode, String runtimeJson) {
        if (!AgentRunMode.ROUTER.name().equals(parentRunMode)) {
            return;
        }
        AgentRuntimeConfig next = AgentRuntimeConfig.parse(runtimeJson, parentRunMode);
        if (!next.isCriticLoop()) {
            return;
        }
        AgentCriticPolicy.assertRoles(next, routes.listByParentId(parentAgentId), lookup::find);
    }

    private static AgentRuntimeConfigResp toRuntimeResp(AgentDef agent) {
        AgentRuntimeConfig config = AgentRuntimeConfig.parse(agent.getRuntimeConfigJson(), agent.getRunMode());
        AgentRuntimeConfigResp resp = new AgentRuntimeConfigResp();
        resp.setMaTemplate(config.maTemplateOrDefault());
        resp.setMaContextPolicy(config.maContextPolicyOrDefault());
        resp.setMaAllowParentHistoryWhenSingle(config.allowParentHistoryWhenSingleOn());
        resp.setMultiIntentEnabled(config.multiIntentOn());
        resp.setAnswerMode(config.answerModeEnum().name());
        resp.setMaxRouteChildren(config.maxRouteChildrenOrDefault());
        resp.setRequirePlanConfirm(config.requirePlanConfirmOn());
        resp.setMaxPlanSteps(config.maxPlanStepsOrDefault());
        resp.setMaxStepRetries(config.maxStepRetriesOrDefault());
        resp.setMaChildRunModesAllowed(config.childRunModesAllowedOrDefault());
        resp.setMaCriticMaxRounds(config.criticMaxRoundsOrDefault());
        resp.setMaCriticAgentId(config.getMaCriticAgentId());
        resp.setMaJudgeModelInstanceId(config.getMaJudgeModelInstanceId());
        resp.setMaJudgeModel(config.getMaJudgeModel());
        resp.setCompeteStrategy(config.competeStrategyOrDefault());
        return resp;
    }

    private static RuntimeLimitsViewResp.LimitBoundView bound(RuntimeLimitsProperties.Bound source) {
        RuntimeLimitsViewResp.LimitBoundView view = new RuntimeLimitsViewResp.LimitBoundView();
        view.setDefaultValue(source.getDefault());
        view.setMax(source.getMax());
        return view;
    }
}
