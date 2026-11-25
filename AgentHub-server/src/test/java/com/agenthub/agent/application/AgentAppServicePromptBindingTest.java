package com.agenthub.agent.application;

import com.agenthub.agent.api.dto.AgentCreateReq;
import com.agenthub.agent.api.dto.AgentDetailResp;
import com.agenthub.agent.api.dto.AgentUpdateReq;
import com.agenthub.agent.api.dto.PromptBindingReq;
import com.agenthub.agent.api.dto.PromptBindingResp;
import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentPromptBinding;
import com.agenthub.agent.domain.model.AgentPromptTemplateSegment;
import com.agenthub.agent.domain.model.AgentPromptTemplateView;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.AgentModelBindRepository;
import com.agenthub.agent.domain.repository.AgentRouteRepository;
import com.agenthub.agent.domain.repository.AgentSkillBindRepository;
import com.agenthub.agent.domain.repository.AgentToolBindRepository;
import com.agenthub.agent.domain.service.AgentCodePolicy;
import com.agenthub.agent.domain.service.AgentPromptProperties;
import com.agenthub.agent.domain.service.AgentPromptTemplatePort;
import com.agenthub.agent.domain.service.PlatformPromptPrefix;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.common.config.RuntimeLimitsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CHAT 挂载点（agent_def 四列）的保存与回显。所属域：agent（test）。
 * <p>
 * 覆盖验收点：CHAT 落 agent_def；ROUTER 模式拒绝走 agent_def（其提示词在 ROUTER 用途槽）；
 * 未传 prompt 时不改动既有 L3 配置。
 * 用内存实现替代 prompt 域端口，不连库、不出站。
 */
class AgentAppServicePromptBindingTest {

    private final AgentDefRepository agents = mock(AgentDefRepository.class);
    private final AtomicReference<AgentDef> stored = new AtomicReference<>();
    private final InMemoryPromptPort promptPort = new InMemoryPromptPort();
    private AgentAppService service;

    @BeforeEach
    void setUp() {
        AgentPromptProperties properties = new AgentPromptProperties();
        PlatformPromptPrefix prefixes = com.agenthub.prompt.PromptTestFixtures.prefix();
        OperatorContext operator = mock(OperatorContext.class);
        when(operator.requireUserId()).thenReturn(1L);
        when(agents.insert(any(), anyLong())).thenAnswer(invocation -> {
            AgentDef agent = invocation.getArgument(0);
            agent.setId(9L);
            stored.set(agent);
            return 9L;
        });
        when(agents.findById(9L)).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        RuntimeLimitsProperties limits = new RuntimeLimitsProperties();
        service = new AgentAppService(agents, mock(AgentModelBindRepository.class),
                mock(AgentToolBindRepository.class), mock(AgentSkillBindRepository.class),
                mock(AgentRouteRepository.class), new AgentCodePolicy(agents), new AgentLookup(agents),
                operator, mock(OperLogWriter.class), new PromptSafetyChecker(properties), prefixes,
                new AgentLimitValidator(limits), limits, promptPort);
    }

    /**
     * CHAT Agent：四列原样落 agent_def，回显带上模板与覆盖。
     */
    @Test
    void chatBindingIsStoredOnAgentDef() {
        PromptBindingReq prompt = new PromptBindingReq();
        prompt.setTemplateId(1L);
        prompt.setTemplateVersion(1);
        prompt.setOverride(Map.of("role_tone", "你是售后顾问"));
        prompt.setPromptExtra("追加一句");

        AgentDetailResp detail = service.create(request("REACT", prompt));

        assertEquals(1L, stored.get().getPromptTemplateId());
        assertEquals(1, stored.get().getTemplateVersion());
        assertTrue(stored.get().getPromptOverrideJson().contains("role_tone"));
        assertEquals("追加一句", stored.get().getPromptExtra());
        // 回显：supported 为 true，覆盖可解析回 Map
        PromptBindingResp echo = detail.getPrompt();
        assertTrue(echo.getSupported());
        assertEquals(1L, echo.getTemplateId());
        assertEquals("你是售后顾问", echo.getOverride().get("role_tone"));
    }

    /**
     * ROUTER 模式的提示词属于 ROUTER 用途槽（agent_model_bind），
     * agent_def 收到非空模板必须拒绝，否则运行时按 ROUTER 用途组装必然阻断。
     */
    @Test
    void routerModeRejectsAgentDefBinding() {
        PromptBindingReq prompt = new PromptBindingReq();
        prompt.setTemplateId(1L);
        prompt.setTemplateVersion(1);

        BizException ex = assertThrows(BizException.class, () -> service.create(request("ROUTER", prompt)));
        assertTrue(ex.getMessage().contains("ROUTER 用途槽"), ex.getMessage());
    }

    /**
     * ROUTER 模式只清空/不配置时不拦：不挂模板是合法状态。
     */
    @Test
    void routerModeAllowsEmptyBinding() {
        PromptBindingReq prompt = new PromptBindingReq();
        AgentDetailResp detail = service.create(request("ROUTER", prompt));
        assertNull(stored.get().getPromptTemplateId());
        assertFalse(detail.getPrompt().getSupported());
    }

    /**
     * 新建未传 prompt：自动钉住平台当前生效的 CHAT 模板（默认绑定），不再留下四列全空。
     */
    @Test
    void nullPromptAutoBindsDefaultChatTemplate() {
        service.create(request("REACT", null));
        assertEquals(1L, stored.get().getPromptTemplateId());
        assertEquals(1, stored.get().getTemplateVersion());
        assertNull(stored.get().getPromptOverrideJson());
        assertNull(stored.get().getPromptExtra());
    }

    /**
     * 未传 prompt 但写了业务提示词：绑定默认模板时把正文并入追加要求并清空旧列，
     * 避免出站时同一段正文被追加两遍（Assembler 会以【业务提示词】再加一次）。
     */
    @Test
    void legacyBusinessPromptIsMergedIntoExtraOnCreate() {
        AgentCreateReq req = request("REACT", null);
        req.setSystemPrompt("你是售后顾问");
        service.create(req);
        assertEquals(1L, stored.get().getPromptTemplateId());
        assertNull(stored.get().getSystemPrompt());
        assertEquals("你是售后顾问", stored.get().getPromptExtra());
    }

    /**
     * 存量未挂模板的 Agent：保存时补钉默认模板（拒绝继续处于「无模板」）。
     */
    @Test
    void updateBackfillsDefaultBindingForLegacyAgent() {
        service.create(request("REACT", null));
        // 模拟存量：库里是未挂模板 + 只有业务正文
        stored.get().setPromptTemplateId(null);
        stored.get().setTemplateVersion(null);
        stored.get().setSystemPrompt("旧业务正文");

        AgentUpdateReq req = updateRequest("REACT");
        // 整份保存会带上当前正文；不传等于清空（现网语义），这里按真实请求传回
        req.setSystemPrompt("旧业务正文");
        service.update(9L, req);

        assertEquals(1L, stored.get().getPromptTemplateId());
        assertEquals(1, stored.get().getTemplateVersion());
        assertNull(stored.get().getSystemPrompt());
        assertEquals("旧业务正文", stored.get().getPromptExtra());
    }

    /**
     * 显式提交「清空模板」也不允许回到未挂模板：按默认绑定补回。
     */
    @Test
    void clearingPromptBindingIsRejectedAndRebound() {
        service.create(request("REACT", null));
        AgentUpdateReq clear = updateRequest("REACT");
        PromptBindingReq empty = new PromptBindingReq();
        clear.setPrompt(empty);

        service.update(9L, clear);
        assertEquals(1L, stored.get().getPromptTemplateId());
        assertEquals(1, stored.get().getTemplateVersion());
    }

    /**
     * 复制 Agent：原样带走源的四列（模板、版本、覆盖、追加），不能打回默认空模板或业务提示词。
     */
    @Test
    void copyInheritsSourcePromptBinding() {
        PromptBindingReq prompt = new PromptBindingReq();
        prompt.setTemplateId(1L);
        prompt.setTemplateVersion(1);
        prompt.setOverride(Map.of("role_tone", "你是售后顾问"));
        prompt.setPromptExtra("追加一句");
        service.create(request("REACT", prompt));
        stored.get().setDescription("源说明");

        com.agenthub.agent.api.dto.AgentCopyReq copyReq = new com.agenthub.agent.api.dto.AgentCopyReq();
        copyReq.setName("助手乙");
        copyReq.setCode("helper-b");
        service.copy(9L, copyReq);

        AgentDef copied = stored.get();
        assertEquals("助手乙", copied.getName());
        assertEquals(1L, copied.getPromptTemplateId());
        assertEquals(1, copied.getTemplateVersion());
        // 覆盖键与追加要求逐列照搬，不被默认模板重置
        assertTrue(copied.getPromptOverrideJson().contains("role_tone"));
        assertEquals("追加一句", copied.getPromptExtra());
        assertNull(copied.getSystemPrompt());
    }

    /**
     * allow_orchestrated 创建默认值：REACT 未传 → 1；ROUTER 传 true 也强制 0（编排根不可作子）。
     */
    @Test
    void createResolvesAllowOrchestratedByRunMode() {
        service.create(request("REACT", null));
        assertEquals(1, stored.get().getAllowOrchestrated());
        assertEquals(Boolean.TRUE, service.get(9L).getAllowOrchestrated());

        // ROUTER：即便请求体传 true，落库必须为 0，回显同样是 false
        AgentCreateReq router = request("ROUTER", null);
        router.setAllowOrchestrated(true);
        service.create(router);
        assertEquals(0, stored.get().getAllowOrchestrated());
        assertEquals(Boolean.FALSE, service.get(9L).getAllowOrchestrated());
    }

    private AgentCreateReq request(String runMode, PromptBindingReq prompt) {
        AgentCreateReq req = new AgentCreateReq();
        req.setName("助手甲");
        req.setCode("helper-a");
        req.setRunMode(runMode);
        req.setPrompt(prompt);
        return req;
    }

    /**
     * 更新的 allow_orchestrated 语义：显式 false 生效；漏传保留已落库值（不悄悄打开）；ROUTER 强制 0。
     */
    @Test
    void updateKeepsAllowFlagWhenOmittedAndForcesOffForRouter() {
        service.create(request("REACT", null));

        AgentUpdateReq off = updateRequest("REACT");
        off.setAllowOrchestrated(false);
        service.update(9L, off);
        assertEquals(0, stored.get().getAllowOrchestrated(), "显式关闭必须生效");

        // 漏传：保留现值，避免整份保存的旁路接口把私有开关打开
        service.update(9L, updateRequest("REACT"));
        assertEquals(0, stored.get().getAllowOrchestrated(), "漏传不能重新打开");

        // 切 ROUTER：即便传 true 也强制 0
        AgentUpdateReq router = updateRequest("ROUTER");
        router.setAllowOrchestrated(true);
        service.update(9L, router);
        assertEquals(0, stored.get().getAllowOrchestrated(), "ROUTER 必须强制关闭");
        assertEquals(Boolean.FALSE, service.get(9L).getAllowOrchestrated());
    }

    private AgentUpdateReq updateRequest(String runMode) {
        AgentUpdateReq req = new AgentUpdateReq();
        req.setName("助手甲");
        req.setCode("helper-a");
        req.setRunMode(runMode);
        return req;
    }

    /**
     * 内存版 L3 端口：只认 CHAT 用途 + 单个已发布版本，段落为 core + soft 各一。
     * 用它替代 prompt 域实现，使本测试专注「四列写到哪里」。
     */
    private static final class InMemoryPromptPort implements AgentPromptTemplatePort {

        private static final long TEMPLATE_ID = 1L;

        @Override
        public boolean supportsPurpose(String purpose) {
            return purpose != null && List.of("CHAT", "ROUTER", "REWRITE", "SUMMARY", "MEMORY")
                    .contains(purpose.toUpperCase());
        }

        @Override
        public List<com.agenthub.agent.domain.model.AgentPromptTemplateOption> publishedOptions(
                String purpose, String locale) {
            // 只有 CHAT 有已发布生效版本；其它用途交给 defaultOption 返回 null，
            // 用来验证「无对应用途模板时保持未挂」而不是拿 CHAT 顶替
            if (purpose == null || !"CHAT".equalsIgnoreCase(purpose)) {
                return List.of();
            }
            return List.of(defaultOptionRow());
        }

        /** 平台当前生效的 CHAT 版本行；与 normalize 认可的版本保持一致 */
        private static com.agenthub.agent.domain.model.AgentPromptTemplateOption defaultOptionRow() {
            com.agenthub.agent.domain.model.AgentPromptTemplateOption option =
                    new com.agenthub.agent.domain.model.AgentPromptTemplateOption();
            option.setId(TEMPLATE_ID);
            option.setTemplateKey("CHAT");
            option.setName("对话主模板");
            option.setPurpose("CHAT");
            option.setLocale("zh-CN");
            option.setVersion(1);
            option.setIsActive(1);
            return option;
        }

        @Override
        public AgentPromptTemplateView view(Long templateId) {
            AgentPromptTemplateView view = new AgentPromptTemplateView();
            view.setId(TEMPLATE_ID);
            view.setPurpose("CHAT");
            view.setVersion(1);
            List<AgentPromptTemplateSegment> segments = new ArrayList<>();
            AgentPromptTemplateSegment core = new AgentPromptTemplateSegment();
            core.setSegKey("core_contract");
            core.setDisplayName("输出契约");
            core.setMutable(0);
            segments.add(core);
            AgentPromptTemplateSegment soft = new AgentPromptTemplateSegment();
            soft.setSegKey("role_tone");
            soft.setDisplayName("角色与语气");
            soft.setMutable(1);
            segments.add(soft);
            view.setSegments(segments);
            return view;
        }

        @Override
        public AgentPromptBinding normalize(String purpose, Long templateId, Integer templateVersion,
                                            Map<String, String> override, String promptExtra) {
            if (templateId == null) {
                if (override != null && !override.isEmpty()) {
                    throw new BizException(com.agenthub.common.error.ErrorCode.BAD_REQUEST,
                            "未选择提示词模板时不能提交段落覆盖");
                }
                return new AgentPromptBinding(null, null, null,
                        promptExtra == null || promptExtra.isBlank() ? null : promptExtra.trim());
            }
            if (templateVersion == null) {
                throw new BizException(com.agenthub.common.error.ErrorCode.BAD_REQUEST,
                        "必须钉死提示词模板版本");
            }
            if (!"CHAT".equalsIgnoreCase(purpose)) {
                throw new BizException(com.agenthub.common.error.ErrorCode.BAD_REQUEST,
                        "模板用途不匹配");
            }
            // 模拟真实闸门：core 段拒绝
            if (override != null && override.containsKey("core_contract")) {
                throw new BizException(com.agenthub.common.error.ErrorCode.BAD_REQUEST,
                        "覆盖段为锁定段：core_contract");
            }
            String json = override == null || override.isEmpty() ? null
                    : override.entrySet().stream()
                    .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                    .map(e -> "\"" + e.getKey() + "\":\"" + e.getValue() + "\"")
                    .reduce((a, b) -> a + "," + b).map(s -> "{" + s + "}").orElse(null);
            return new AgentPromptBinding(templateId, templateVersion, json,
                    promptExtra == null || promptExtra.isBlank() ? null : promptExtra.trim());
        }

        @Override
        public Map<String, String> readOverrideSafe(String overrideJson) {
            Map<String, String> map = new LinkedHashMap<>();
            if (overrideJson == null || overrideJson.isBlank()) {
                return map;
            }
            // 测试用极简解析：只认 {"k":"v"} 形态，避免引入额外依赖
            String body = overrideJson.trim();
            if (body.startsWith("{") && body.endsWith("}")) {
                body = body.substring(1, body.length() - 1);
            }
            for (String pair : body.split(",")) {
                String[] kv = pair.split(":", 2);
                if (kv.length == 2) {
                    map.put(kv[0].replace("\"", "").trim(), kv[1].replace("\"", "").trim());
                }
            }
            return map;
        }

        @Override
        public com.agenthub.agent.domain.model.AgentPromptPreviewResult preview(
                String purpose, String locale, Long templateId, Integer templateVersion,
                Map<String, String> override, String promptExtra, String agentName, String userQuery) {
            throw new UnsupportedOperationException("本测试不覆盖预览，预览另在 prompt 域单测中验证");
        }
    }
}
