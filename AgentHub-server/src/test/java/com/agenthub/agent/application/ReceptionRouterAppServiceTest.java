package com.agenthub.agent.application;

import com.agenthub.agent.api.dto.PromptBindingResp;
import com.agenthub.agent.api.dto.PromptTemplateViewResp;
import com.agenthub.agent.api.dto.ReceptionRouterConfigResp;
import com.agenthub.agent.domain.model.MaDef;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 默认接待页入口 Router 只读回显。所属域：agent。
 *
 * <p>本刀起入口的模型与提示词编辑走现网方案编辑页，这里只剩读：
 * <ul>
 *   <li>回显入口方案（含 ENTRY_ROUTER 模板码）与就绪状态；</li>
 *   <li>「实际生效模型」按 显式绑定 → 别名 → 平台槽 回显，页面不允许猜；</li>
 *   <li>提示词绑定复用专家那一套转换，不新造字段。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ReceptionRouterAppServiceTest {

    @Mock
    private ReceptionSchemeResolver reception;
    @Mock
    private ModelInstanceRepository instances;
    @Mock
    private AgentPromptAppService prompts;

    private ReceptionRouterAppService service;

    @BeforeEach
    void setUp() {
        service = new ReceptionRouterAppService(reception, instances, prompts);
        // 回显提示词时统一走同一个空绑定，避免每个用例都重复 stub
        lenient().when(prompts.toResp(eq("ROUTER"), any(), any(), any(), any()))
                .thenReturn(new PromptBindingResp());
    }

    /** 未配置默认接待：就绪为 false，模型来源 NONE，提示词对象仍需非空供页面判空。 */
    @Test
    void configWithoutSchemeReportsNotReady() {
        when(reception.snapshot()).thenReturn(new ReceptionSchemeResolver.Snapshot(
                null, List.of(), false, "尚未设置默认接待方案", null));

        ReceptionRouterConfigResp resp = service.config();

        assertFalse(resp.isReady());
        assertEquals("NONE", resp.getJudgeModelSource());
        assertNotNull(resp.getPrompt());
        assertNull(resp.getSchemeId());
    }

    /** 入口方案回显：模板码必须带出来；展示名读库内名称（本刀起可编辑，不再强制「Router」）。 */
    @Test
    void configExposesEntryTemplate() {
        MaDef def = def(null, "ENTRY_ROUTER");
        when(reception.snapshot()).thenReturn(new ReceptionSchemeResolver.Snapshot(
                def, List.of(), true, null, null));

        ReceptionRouterConfigResp resp = service.config();

        assertEquals("ENTRY_ROUTER", resp.getSchemeTemplate());
        assertEquals("默认接待", resp.getSchemeName());
    }

    /** 方案显式绑定实例：页面显示的必须是这一套，而不是平台回落槽。 */
    @Test
    void configPrefersSchemeBoundInstance() {
        MaDef def = def("{\"maJudgeModelInstanceId\":7}", "ENTRY_ROUTER");
        when(reception.snapshot()).thenReturn(new ReceptionSchemeResolver.Snapshot(
                def, List.of(), true, null, 99L));
        when(instances.findById(7L)).thenReturn(Optional.of(instance(7L, "JEV-判定", "jev-router", "ROUTER", 1)));

        ReceptionRouterConfigResp resp = service.config();

        assertEquals("SCHEME", resp.getJudgeModelSource());
        assertEquals("JEV-判定", resp.getJudgeModelName());
        assertEquals("jev-router", resp.getJudgeModelModelId());
        assertEquals(7L, resp.getJudgeModelInstanceId());
    }

    /** 未显式绑定：回显平台槽并明确标注来源为 PLATFORM，让「现在用哪套」可见。 */
    @Test
    void configFallsBackToPlatformSlot() {
        when(reception.snapshot()).thenReturn(new ReceptionSchemeResolver.Snapshot(
                def(null, "ENTRY_ROUTER"), List.of(), true, null, 99L));
        when(instances.findById(99L)).thenReturn(Optional.of(instance(99L, "平台对话", "gpt-4o-mini", "CHAT", 1)));

        ReceptionRouterConfigResp resp = service.config();

        assertEquals("PLATFORM", resp.getJudgeModelSource());
        assertEquals("平台对话", resp.getJudgeModelName());
        assertNull(resp.getJudgeModelInstanceId());
    }

    /** 回显钉死模板时补模板名，页面摘要能直接写「路由判定模板 · v2」。 */
    @Test
    void configFillsPinnedTemplateName() {
        MaDef def = def("{\"maJudgePromptTemplateId\":31,\"maJudgePromptTemplateVersion\":2}",
                "ENTRY_ROUTER");
        when(reception.snapshot()).thenReturn(new ReceptionSchemeResolver.Snapshot(def, List.of(), true, null, 9L));
        when(instances.findById(9L)).thenReturn(Optional.of(instance(9L, "平台对话", "gpt-4o-mini", "CHAT", 1)));
        PromptTemplateViewResp view = new PromptTemplateViewResp();
        view.setName("路由判定模板");
        view.setTemplateKey("ROUTER");
        when(prompts.view(31L)).thenReturn(view);

        ReceptionRouterConfigResp resp = service.config();

        assertEquals("路由判定模板", resp.getPromptTemplateName());
        assertEquals("ROUTER", resp.getPromptTemplateKey());
    }

    private static MaDef def(String configJson, String template) {
        MaDef def = new MaDef();
        def.setId(42L);
        def.setTenantId(1L);
        def.setCode("reception_default");
        def.setName("默认接待");
        def.setTemplate(template);
        def.setConfigJson(configJson == null
                ? "{\"maChildRunModesAllowed\":[\"REACT\"]}"
                : configJson);
        def.setEnabled(1);
        return def;
    }

    private static ModelInstance instance(Long id, String name, String modelId, String capability, int status) {
        ModelInstance instance = new ModelInstance();
        instance.setId(id);
        instance.setDisplayName(name);
        instance.setModelId(modelId);
        instance.setCapability(capability);
        instance.setStatus(status);
        return instance;
    }
}
