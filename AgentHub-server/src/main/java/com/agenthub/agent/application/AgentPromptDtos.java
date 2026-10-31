package com.agenthub.agent.application;

import com.agenthub.agent.api.dto.PromptBindingResp;
import com.agenthub.agent.domain.service.AgentPromptTemplatePort;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * L3 回显 DTO 的统一转换。所属域：agent（application）。
 * <p>
 * 为什么单独抽一个包内工具：{@code agent_def}（CHAT）与 {@code agent_model_bind}（非 CHAT）
 * 四列语义完全相同，若在两个 AppService 各写一份 map 解析与 supported 判定，
 * 迟早出现「CHAT 页能回显、绑定页回显为空」的错位。
 * <p>
 * 纯函数、无状态；只做读路径转换，不做任何写库或校验。
 */
final class AgentPromptDtos {

    private AgentPromptDtos() {
    }

    /**
     * 库内四列 → 回显对象。
     *
     * @param port            L3 端口（提供 supported 判定与容错解析）
     * @param purpose         配置用途；决定 supported
     * @param templateId      模板主键，可空
     * @param templateVersion 钉死版本，可空
     * @param overrideJson    库内 JSON，可空
     * @param promptExtra     追加区，可空
     * @return 回显对象；四列全空时 templateId 为空、override 为空 Map
     */
    static PromptBindingResp toResp(AgentPromptTemplatePort port, String purpose, Long templateId,
                                    Integer templateVersion, String overrideJson, String promptExtra) {
        PromptBindingResp resp = new PromptBindingResp();
        resp.setSupported(port.supportsPurpose(purpose));
        resp.setTemplateId(templateId);
        resp.setTemplateVersion(templateVersion);
        Map<String, String> parsed = port.readOverrideSafe(overrideJson);
        resp.setOverride(parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed));
        resp.setPromptExtra(promptExtra);
        return resp;
    }
}
