package com.agenthub.memory.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.common.api.Result;
import com.agenthub.memory.application.MemoryPolicyAppService;
import com.agenthub.memory.application.MemoryRecallFacade;
import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.service.ContextWindowStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 召回和窗口调试。所属域：memory。
 * 不单开菜单码，复用 memory:policy:list，避免把正文调试口暴露给只有条目查询的账号。
 */
@RestController
@RequestMapping("/api/memory/debug")
public class MemoryDebugController {

    private final MemoryPolicyAppService policies;
    private final MemoryRecallFacade recall;
    private final ContextWindowStore windows;

    /**
     * @param policies 策略
     * @param recall   SHORT / LONG / AGENT
     * @param windows  只读当前热窗口，不在调试口触发摘要
     */
    public MemoryDebugController(MemoryPolicyAppService policies, MemoryRecallFacade recall, ContextWindowStore windows) {
        this.policies = policies;
        this.recall = recall;
        this.windows = windows;
    }

    /**
     * @param agentId   Agent
     * @param sessionId 会话，查 SHORT 时必填
     * @param userId    用户，查 LONG 时使用
     * @return 三段文本的长度和条数，正文只给有策略查询权的人
     */
    @SaCheckPermission(MemoryPermissions.POLICY_LIST)
    @GetMapping("/recall")
    public Result<Map<String, Object>> recall(@RequestParam Long agentId,
                                              @RequestParam(required = false) Long sessionId,
                                              @RequestParam(required = false) Long userId) {
        MemoryPolicy policy = policies.effective(agentId);
        List<MemoryItem> shorts = sessionId == null ? List.of() : recall.shorts(policy, agentId, sessionId);
        MemoryRecallFacade.Durable durable = recall.durable(policy, agentId, userId == null ? 0L : userId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shortCount", shorts.size());
        body.put("agentChars", durable.agent().length());
        body.put("longChars", durable.pref().length());
        body.put("shorts", shorts.stream().map(MemoryItem::getContent).toList());
        body.put("agent", durable.agent());
        body.put("long", durable.pref());
        return Result.ok(body);
    }

    /**
     * @param sessionId 会话
     * @return 摘要和近轮。窗口 key 不存在时 turns 为 null，表示尚未回补
     */
    @SaCheckPermission(MemoryPermissions.POLICY_LIST)
    @GetMapping("/context")
    public Result<Map<String, Object>> context(@RequestParam Long sessionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        windows.readTurns(sessionId).ifPresentOrElse(
                turns -> body.put("turns", turns),
                () -> body.put("turns", null));
        body.put("summary", windows.readSummary(sessionId));
        return Result.ok(body);
    }
}
