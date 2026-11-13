package com.agenthub.agent.application;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.agent.api.dto.PromptTemplateViewResp;
import com.agenthub.agent.api.dto.ReceptionRouterConfigResp;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.MaDef;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 默认接待页的「工作台入口 Router」只读回显。所属域：agent 的 application。
 *
 * <p>本刀把入口的<b>编辑</b>收回到现网方案编辑页（{@code /orchestration/multi-agent?edit=<id>}）：
 * 模型槽与三段式 ROUTER 提示词都落在同一条默认接待方案的 {@code ma_def.config_json} 上，
 * 由 {@code MaDefAppService} 用与专家相同的 {@code normalize} 闸门保存。
 * 本类因此只剩<b>读</b>：默认接待页顶部用一行状态告诉运营「入口是谁、现在用哪套模型、提示词钉了哪版」，
 * 再给一个跳转按钮，不再复制第二张表单。
 *
 * <p>边界：不写库、不校验凭证、不出站；页面显示与工作台执行侧读的是同一份配置。
 */
@Service
public class ReceptionRouterAppService {

    /**
     * Router 提示词用途：固定 ROUTER。
     * <p>取值与 prompt 域的 {@code PromptPurpose.ROUTER} 对齐；此处用字面量，
     * 避免 agent 域反过来依赖 prompt 域的枚举。
     */
    private static final String PURPOSE_ROUTER = "ROUTER";

    private final ReceptionSchemeResolver reception;
    private final ModelInstanceRepository instances;
    private final AgentPromptAppService prompts;

    /**
     * @param reception  默认接待解析（方案、就绪状态、平台回落的判路由模型）
     * @param instances  模型实例（把实例 id 翻成页面可读的名称）
     * @param prompts    L3 提示词读路径（与专家配置同一套回显转换）
     */
    public ReceptionRouterAppService(ReceptionSchemeResolver reception,
                                     ModelInstanceRepository instances, AgentPromptAppService prompts) {
        this.reception = reception;
        this.instances = instances;
        this.prompts = prompts;
    }

    /**
     * 回显入口配置摘要：方案信息、显式绑定、实际生效模型、ROUTER 提示词绑定。
     *
     * <p>「实际生效」与就绪判定同源：方案显式绑定优先，其次别名，最后平台 ROUTER / CHAT 槽，
     * 因此页面上写的就是工作台这一轮真正会用的那套，不需要用户去别处猜。
     *
     * @return 配置视图；默认接待未配置时 schemeId 为空、ready=false
     */
    public ReceptionRouterConfigResp config() {
        ReceptionSchemeResolver.Snapshot snapshot = reception.snapshot();
        ReceptionRouterConfigResp resp = new ReceptionRouterConfigResp();
        resp.setReady(snapshot.ready());
        resp.setReason(snapshot.reason());
        // 提示词绑定即使方案缺失也给空对象，前端可直接展示「未钉模板」
        resp.setPrompt(prompts.toResp(PURPOSE_ROUTER, null, null, null, null));

        MaDef def = snapshot.def();
        if (def == null) {
            resp.setJudgeModelSource("NONE");
            return resp;
        }
        resp.setSchemeId(def.getId());
        resp.setSchemeCode(def.getCode());
        resp.setSchemeName(def.displayName());
        resp.setSchemeTemplate(def.getTemplate());

        AgentRuntimeConfig runtime = runtimeOf(def);
        resp.setJudgeModelInstanceId(runtime.getMaJudgeModelInstanceId());
        resp.setJudgeModelAlias(runtime.getMaJudgeModel());
        resp.setPrompt(prompts.toResp(PURPOSE_ROUTER, runtime.getMaJudgePromptTemplateId(),
                runtime.getMaJudgePromptTemplateVersion(), runtime.getMaJudgePromptOverrideJson(),
                runtime.getMaJudgePromptExtra()));
        fillPromptTemplateSummary(resp, runtime.getMaJudgePromptTemplateId());

        Resolution resolution = resolveEffectiveModel(runtime, snapshot);
        resp.setJudgeModelSource(resolution.source());
        if (resolution.instance() != null) {
            resp.setJudgeModelName(resolution.instance().getDisplayName());
            resp.setJudgeModelModelId(resolution.instance().getModelId());
            resp.setJudgeModelCapability(resolution.instance().getCapability());
        }
        return resp;
    }

    /**
     * 给页面摘要补「钉了哪份模板」：只读一次模板行，失败静默（模板下架不影响回显其它项）。
     *
     * @param resp       回显对象
     * @param templateId 钉死模板主键；可空
     */
    private void fillPromptTemplateSummary(ReceptionRouterConfigResp resp, Long templateId) {
        if (templateId == null) {
            return;
        }
        try {
            PromptTemplateViewResp view = prompts.view(templateId);
            resp.setPromptTemplateName(view.getName());
            resp.setPromptTemplateKey(view.getTemplateKey());
        } catch (RuntimeException ex) {
            // 模板已删除 / 未发布：页面按「引用已失效」提示，不影响模型槽回显
        }
    }

    /**
     * 实际生效的判路由模型：方案显式绑定 → 别名匹配 → 平台 ROUTER / CHAT 槽。
     *
     * <p>回显时只读，不做任何写库；找不到就返回 NONE，前端显示「未就绪」。
     *
     * @param runtime  方案配置
     * @param snapshot 默认接待快照（携带平台回落实例）
     * @return 生效实例与来源；没有可用实例时 instance 为空
     */
    private Resolution resolveEffectiveModel(AgentRuntimeConfig runtime,
                                             ReceptionSchemeResolver.Snapshot snapshot) {
        Long explicit = runtime.getMaJudgeModelInstanceId();
        if (explicit != null) {
            // 显式绑定存在但不可用：不静默换成平台槽，页面必须显示真实原因（与就绪判定同口径）
            Optional<ModelInstance> found = instances.findById(explicit).filter(this::usable);
            return found.map(instance -> new Resolution(instance, "SCHEME"))
                    .orElse(new Resolution(null, "NONE"));
        }
        String alias = runtime.getMaJudgeModel();
        if (alias != null && !alias.isBlank()) {
            // 别名由执行侧解析，这里按同一优先级回显；匹配不到就交给「未就绪」文案说明
            return matchAlias(alias).map(instance -> new Resolution(instance, "SCHEME_ALIAS"))
                    .orElse(new Resolution(null, "NONE"));
        }
        Long platform = snapshot.judgeModelInstanceId();
        if (platform == null) {
            return new Resolution(null, "NONE");
        }
        return instances.findById(platform).filter(this::usable)
                .map(instance -> new Resolution(instance, "PLATFORM"))
                .orElse(new Resolution(null, "NONE"));
    }

    /**
     * 别名匹配：纯数字按实例 id，否则按启用中实例的 {@code model_id} 或展示名大小写不敏感匹配。
     *
     * <p>与 {@code MaTrialSpecLoader#resolveJudgeModel} 同口径，保证页面显示与执行侧一致。
     *
     * @param alias 方案上配置的别名
     * @return 命中的实例；没有为空
     */
    private Optional<ModelInstance> matchAlias(String alias) {
        String text = alias.trim();
        if (text.chars().allMatch(Character::isDigit) && text.length() <= 18) {
            try {
                return instances.findById(Long.valueOf(text)).filter(this::usable);
            } catch (NumberFormatException ex) {
                return Optional.empty();
            }
        }
        String wanted = text.toLowerCase(Locale.ROOT);
        for (ModelCapability capability : List.of(ModelCapability.ROUTER, ModelCapability.CHAT)) {
            for (ModelInstance instance : instances.listEnabledByCapability(capability.name())) {
                if (!usable(instance)) {
                    continue;
                }
                String modelId = instance.getModelId() == null ? "" : instance.getModelId().toLowerCase(Locale.ROOT);
                String name = instance.getDisplayName() == null
                        ? "" : instance.getDisplayName().toLowerCase(Locale.ROOT);
                if (wanted.equals(modelId) || wanted.equals(name)) {
                    return Optional.of(instance);
                }
            }
        }
        return Optional.empty();
    }

    /** 方案配置：模板以 ma_def.template 为准，避免 config_json 的同名字段漂移。 */
    private AgentRuntimeConfig runtimeOf(MaDef def) {
        AgentRuntimeConfig runtime = AgentRuntimeConfig.parse(def.getConfigJson(), AgentRunMode.ROUTER.name());
        runtime.setMaTemplate(def.getTemplate());
        return runtime;
    }

    /**
     * 生效模型与来源的一次性快照。
     *
     * @param instance 生效实例；没有可用实例时为 null
     * @param source   SCHEME / SCHEME_ALIAS / PLATFORM / NONE
     */
    private record Resolution(ModelInstance instance, String source) {
    }

    /**
     * 实例可用：存在、已启用、用途走对话形态。
     *
     * @param instance 模型实例
     * @return true 表示可以拿来判路由
     */
    private boolean usable(ModelInstance instance) {
        if (instance == null || !RecordStatus.isEnabled(instance.getStatus())) {
            return false;
        }
        return ModelCapability.tryParse(instance.getCapability())
                .map(ModelCapability::usesChat)
                .orElse(false);
    }
}
