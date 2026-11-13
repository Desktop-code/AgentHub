package com.agenthub.agent.application;

import com.agenthub.agent.api.dto.PromptBindingReq;
import com.agenthub.agent.api.dto.PromptBindingResp;
import com.agenthub.agent.api.dto.PromptPreviewReq;
import com.agenthub.agent.api.dto.PromptPreviewResp;
import com.agenthub.agent.api.dto.PromptTemplateOptionResp;
import com.agenthub.agent.api.dto.PromptTemplateSegmentResp;
import com.agenthub.agent.api.dto.PromptTemplateViewResp;
import com.agenthub.agent.domain.model.AgentPromptBinding;
import com.agenthub.agent.domain.model.AgentPromptPreviewResult;
import com.agenthub.agent.domain.model.AgentPromptTemplateOption;
import com.agenthub.agent.domain.model.AgentPromptTemplateSegment;
import com.agenthub.agent.domain.model.AgentPromptTemplateView;
import com.agenthub.agent.domain.service.AgentPromptTemplatePort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * L3 提示词配置的读路径与预览用例。所属域：agent。
 * <p>
 * 职责：把 prompt 域的端口结果转成 API DTO，并统一「库里四列 → 回显对象」的转换，
 * 让 CHAT（agent_def）与非 CHAT（agent_model_bind）共用同一份读取口径。
 * <p>
 * 约束：
 * 1. 本类不写库；保存校验在 {@link AgentPromptTemplatePort#normalize} 与两个写用例里；
 * 2. 预览**必须**经端口走到 PromptAssembler，禁止在此另拼一份正文；
 * 3. 不调模型、不出站。
 */
@Service
public class AgentPromptAppService {

    private final AgentPromptTemplatePort promptTemplates;

    /**
     * @param promptTemplates L3 端口（实现在 prompt 域）
     */
    public AgentPromptAppService(AgentPromptTemplatePort promptTemplates) {
        this.promptTemplates = promptTemplates;
    }

    /**
     * 该用途可选的已发布模板版本，供下拉选择。
     *
     * @param purpose 用途码
     * @param locale  语言；空白由实现回落 zh-CN
     * @return 版本行；用途无模板维度时为空列表
     */
    public List<PromptTemplateOptionResp> options(String purpose, String locale) {
        List<PromptTemplateOptionResp> items = new ArrayList<>();
        for (AgentPromptTemplateOption option : promptTemplates.publishedOptions(purpose, locale)) {
            items.add(toOptionResp(option));
        }
        return items;
    }

    /**
     * 平台该用途当前生效的模板版本（新建 Agent 默认钉住的那一条）。
     * <p>
     * 前端新建 / 存量补钉时用它回显「将绑定哪条模板 + 哪一版」，避免页面自己按 is_active 猜一套口径。
     *
     * @param purpose 用途码
     * @param locale  语言；空白由端口回落 zh-CN
     * @return 生效版本；该用途没有已发布生效模板时为 null
     */
    public PromptTemplateOptionResp defaultOption(String purpose, String locale) {
        AgentPromptTemplateOption option = promptTemplates.defaultOption(purpose, locale);
        return option == null ? null : toOptionResp(option);
    }

    /**
     * 版本行 → 下拉 / 摘要 DTO。
     *
     * @param option prompt 域的版本行
     * @return API DTO
     */
    private PromptTemplateOptionResp toOptionResp(AgentPromptTemplateOption option) {
        PromptTemplateOptionResp resp = new PromptTemplateOptionResp();
        resp.setId(option.getId());
        resp.setTemplateKey(option.getTemplateKey());
        resp.setName(option.getName());
        resp.setPurpose(option.getPurpose());
        resp.setLocale(option.getLocale());
        resp.setVersion(option.getVersion());
        resp.setIsActive(option.getIsActive());
        resp.setUpdateTime(option.getUpdateTime());
        return resp;
    }

    /**
     * 钉死版（或待升级版）模板及其段落，供 L3 表单按 mutable 分栏渲染。
     *
     * @param templateId 版本行主键
     * @return 模板与段落
     */
    public PromptTemplateViewResp view(Long templateId) {
        AgentPromptTemplateView view = promptTemplates.view(templateId);
        PromptTemplateViewResp resp = new PromptTemplateViewResp();
        resp.setId(view.getId());
        resp.setTemplateKey(view.getTemplateKey());
        resp.setName(view.getName());
        resp.setPurpose(view.getPurpose());
        resp.setLocale(view.getLocale());
        resp.setVersion(view.getVersion());
        resp.setIsActive(view.getIsActive());
        List<PromptTemplateSegmentResp> items = new ArrayList<>();
        for (AgentPromptTemplateSegment segment : view.getSegments() == null ? List.<AgentPromptTemplateSegment>of()
                : view.getSegments()) {
            PromptTemplateSegmentResp item = new PromptTemplateSegmentResp();
            item.setSegKey(segment.getSegKey());
            item.setDisplayName(segment.getDisplayName());
            item.setSortNo(segment.getSortNo());
            item.setMutable(segment.getMutable());
            item.setRequired(segment.getRequired());
            item.setContent(segment.getContent());
            items.add(item);
        }
        resp.setSegments(items);
        return resp;
    }

    /**
     * 预览未保存的表单值。走与出站相同的组装路径，缺 L1 / 缺模板与运行时同语义失败。
     *
     * @param req 用途 + 四列表单值
     * @return 正文与版本统计
     */
    public PromptPreviewResp preview(PromptPreviewReq req) {
        AgentPromptPreviewResult result = promptTemplates.preview(req.getPurpose(), req.getLocale(),
                req.getTemplateId(), req.getTemplateVersion(), req.getOverride(), req.getPromptExtra(),
                req.getAgentName(), req.getUserQuery());
        PromptPreviewResp resp = new PromptPreviewResp();
        resp.setText(result.getText());
        resp.setPurpose(result.getPurpose());
        resp.setLocale(result.getLocale());
        resp.setConstraintVersion(result.getConstraintVersion());
        resp.setTemplateKey(result.getTemplateKey());
        resp.setTemplateVersion(result.getTemplateVersion());
        resp.setSegmentCount(result.getSegmentCount());
        resp.setOverriddenSegmentCount(result.getOverriddenSegmentCount());
        resp.setTruncated(result.isTruncated());
        resp.setNotices(result.getNotices() == null ? List.of() : result.getNotices());
        return resp;
    }

    /**
     * 保存闸的薄封装：把 DTO 交给端口校验并规范化。CHAT 与非 CHAT 两个写用例共用，保证只有一套规则。
     *
     * @param purpose 配置用途
     * @param req     前端提交的四列；调用方须先判空
     * @return 可入库的绑定值
     */
    public AgentPromptBinding normalize(String purpose, PromptBindingReq req) {
        return promptTemplates.normalize(purpose, req.getTemplateId(), req.getTemplateVersion(),
                req.getOverride(), req.getPromptExtra());
    }

    /**
     * 库内四列 → 回显对象。CHAT 与非 CHAT 共用，避免两套读取口径。
     *
     * @param purpose         用途码；决定 supported
     * @param templateId      模板主键，可空
     * @param templateVersion 钉死版本，可空
     * @param overrideJson    库内 JSON，可空
     * @param promptExtra     追加区，可空
     * @return 回显对象
     */
    public PromptBindingResp toResp(String purpose, Long templateId, Integer templateVersion,
                                    String overrideJson, String promptExtra) {
        return AgentPromptDtos.toResp(promptTemplates, purpose, templateId, templateVersion,
                overrideJson, promptExtra);
    }
}
