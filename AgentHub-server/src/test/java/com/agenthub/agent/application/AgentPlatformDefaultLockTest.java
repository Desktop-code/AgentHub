package com.agenthub.agent.application;

import com.agenthub.agent.api.dto.AgentStatusReq;
import com.agenthub.agent.api.dto.AgentUpdateReq;
import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.AgentModelBindRepository;
import com.agenthub.agent.domain.repository.AgentRouteRepository;
import com.agenthub.agent.domain.repository.AgentSkillBindRepository;
import com.agenthub.agent.domain.repository.AgentToolBindRepository;
import com.agenthub.agent.domain.service.AgentCodePolicy;
import com.agenthub.agent.domain.service.AgentPromptTemplatePort;
import com.agenthub.agent.domain.service.PlatformDefaultAssistantPolicy;
import com.agenthub.agent.domain.service.PlatformPromptPrefix;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.config.RuntimeLimitsProperties;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台默认智能助手的接口硬闸。所属域：agent（测试）。
 *
 * <p>页面隐藏入口只是体验层，这里验证绕过列表直调接口时：
 * 停用 / 删除被拒、编码不可改；同时名称与简介仍可保存，
 * 用户自建专家不受影响（仍允许停用 / 删除 / 改码）。
 */
class AgentPlatformDefaultLockTest {

    private static final String DEFAULT_CODE = PlatformDefaultAssistantPolicy.DEFAULT_ASSISTANT_CODE;

    private final AgentDefRepository agents = mock(AgentDefRepository.class);
    private final AgentModelBindRepository modelBinds = mock(AgentModelBindRepository.class);
    private final AgentToolBindRepository toolBinds = mock(AgentToolBindRepository.class);
    private final AgentSkillBindRepository skillBinds = mock(AgentSkillBindRepository.class);
    private final AgentRouteRepository routes = mock(AgentRouteRepository.class);
    private final OperLogWriter operLog = mock(OperLogWriter.class);
    /** 提示词安全检查：只验启停 / 删除 / 改码三道闸，正文一律放行 */
    private final PromptSafetyChecker safety = mock(PromptSafetyChecker.class);
    private AgentAppService service;

    /**
     * 组装待测服务：只关心启停 / 删除两道闸，其余协作者用 mock，
     * 不连库、不读提示词模板。
     */
    @BeforeEach
    void setUp() {
        OperatorContext operator = mock(OperatorContext.class);
        when(operator.requireUserId()).thenReturn(1L);
        RuntimeLimitsProperties limits = new RuntimeLimitsProperties();
        // 正文检查返回「无错无告警」，让改名 / 改简介走到落库；本测试不验提示词安全策略
        when(safety.check(any())).thenReturn(new PromptSafetyChecker.PromptCheckResult(true,
                java.util.List.of(), java.util.List.of()));
        service = new AgentAppService(agents, modelBinds, toolBinds, skillBinds, routes,
                new AgentCodePolicy(agents), new AgentLookup(agents), operator, operLog,
                safety, mock(PlatformPromptPrefix.class),
                new AgentLimitValidator(limits), limits, mock(AgentPromptTemplatePort.class));
    }

    /** 直调停用平台默认助手：400 拒绝，且不落库。 */
    @Test
    void cannotDisableDefaultAssistant() {
        when(agents.findById(7L)).thenReturn(Optional.of(definition(7L, DEFAULT_CODE)));

        BizException error = assertThrows(BizException.class, () -> service.changeStatus(7L, status(0)));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("不可禁用"));
        verify(agents, never()).updateStatus(anyLong(), anyInt(), anyLong());
        verify(operLog, never()).write(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    /** 直调删除平台默认助手：400 拒绝，且不清绑定。 */
    @Test
    void cannotRemoveDefaultAssistant() {
        when(agents.findById(7L)).thenReturn(Optional.of(definition(7L, DEFAULT_CODE)));

        BizException error = assertThrows(BizException.class, () -> service.remove(7L));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("不可删除"));
        verify(agents, never()).logicalDelete(anyLong(), anyLong());
        verify(modelBinds, never()).logicalDeleteByAgentId(anyLong(), anyLong());
        verify(toolBinds, never()).logicalDeleteByAgentId(anyLong(), anyLong());
        verify(skillBinds, never()).logicalDeleteByAgentId(anyLong(), anyLong());
        verify(routes, never()).logicalDeleteByAgentId(anyLong(), anyLong());
    }

    /** 改名 / 改简介放行：默认助手这两项不在锁定范围，落库且编码保持不变。 */
    @Test
    void renameAndDescriptionAreAllowed() {
        AgentDef existing = definition(7L, DEFAULT_CODE);
        // 已挂模板：更新时走「未提交 prompt 即保留原四列」，避开默认补钉分支
        existing.setPromptTemplateId(3L);
        when(agents.findById(7L)).thenReturn(Optional.of(existing));

        AgentUpdateReq req = updateRequest("接待小助手", DEFAULT_CODE);
        req.setDescription("平台兜底接待，这里改成自定义简介");
        service.update(7L, req);

        assertEquals("接待小助手", existing.getName());
        assertEquals("平台兜底接待，这里改成自定义简介", existing.getDescription());
        // 编码仍是原值：列表 / 消息流都读这一行，无需第二份展示名
        assertEquals(DEFAULT_CODE, existing.getCode());
        verify(agents).updateProfile(eq(existing), anyLong());
    }

    /** 改编码被拒：编码是锁定口径的锚点，改掉就能绕过停用 / 删除闸。 */
    @Test
    void cannotChangeDefaultAssistantCode() {
        AgentDef existing = definition(7L, DEFAULT_CODE);
        existing.setPromptTemplateId(3L);
        when(agents.findById(7L)).thenReturn(Optional.of(existing));

        BizException error = assertThrows(BizException.class,
                () -> service.update(7L, updateRequest("接待小助手", "reception_assistant_new")));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("编码不可修改"));
        assertEquals(DEFAULT_CODE, existing.getCode());
        verify(agents, never()).updateProfile(any(), anyLong());
    }

    /** 仅大小写不同视为未修改：落库仍是原编码，不做无声改写。 */
    @Test
    void sameCodeIgnoringCaseIsAccepted() {
        AgentDef existing = definition(7L, DEFAULT_CODE);
        existing.setPromptTemplateId(3L);
        when(agents.findById(7L)).thenReturn(Optional.of(existing));

        service.update(7L, updateRequest("接待小助手", " Reception_Assistant "));

        assertEquals(DEFAULT_CODE, existing.getCode());
    }

    /** 保留编码不能被别的行占用：避免出现第二条「被锁的兜底」。 */
    @Test
    void reservedCodeCannotBeUsedByOtherAgent() {
        BizException error = assertThrows(BizException.class,
                () -> PlatformDefaultAssistantPolicy.assertCodeNotReserved(DEFAULT_CODE, false));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("保留"));
        // 默认助手那一行自己写回同一编码不受影响
        assertDoesNotThrow(() -> PlatformDefaultAssistantPolicy.assertCodeNotReserved(DEFAULT_CODE, true));
    }

    /** 用户自建专家：策略放行停用与删除，锁定不外溢。 */
    @Test
    void ordinaryAgentIsNotLocked() {
        assertFalse(PlatformDefaultAssistantPolicy.isDefaultAssistant("knowledge_assistant"));
        assertDoesNotThrow(() -> PlatformDefaultAssistantPolicy.assertCanChangeStatus("knowledge_assistant", 0));
        assertDoesNotThrow(() -> PlatformDefaultAssistantPolicy.assertCanRemove("knowledge_assistant"));
        // 大小写 / 首尾空白被手工改过时仍按同一身份锁定，避免改皮绕过
        assertTrue(PlatformDefaultAssistantPolicy.isDefaultAssistant(" Reception_Assistant "));
    }

    /** 构造最小定义：只需 id / code / name / 运行模式（REACT 走单兵保存分支）。 */
    private AgentDef definition(Long id, String code) {
        AgentDef def = new AgentDef();
        def.setId(id);
        def.setCode(code);
        def.setName("平台默认助手");
        def.setRunMode("REACT");
        def.setStatus(1);
        return def;
    }

    /** 构造更新入参：只提交名称与编码，其余字段留空走「不改」语义。 */
    private AgentUpdateReq updateRequest(String name, String code) {
        AgentUpdateReq req = new AgentUpdateReq();
        req.setName(name);
        req.setCode(code);
        req.setRunMode("REACT");
        return req;
    }

    /** 构造启停入参。 */
    private AgentStatusReq status(int value) {
        AgentStatusReq req = new AgentStatusReq();
        req.setStatus(value);
        return req;
    }
}
