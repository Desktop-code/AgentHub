package com.agenthub.memory.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.common.api.Result;
import com.agenthub.memory.api.dto.MemoryPolicyReq;
import com.agenthub.memory.api.dto.MemoryPolicyResp;
import com.agenthub.memory.application.MemoryPolicyAppService;
import com.agenthub.memory.domain.model.MemoryPolicy;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 记忆策略接口。所属域：memory。每个 Agent 至多一条有效策略。
 */
@RestController
@RequestMapping("/api/memory/policies")
public class MemoryPolicyController {

    private final MemoryPolicyAppService policies;

    /**
     * @param policies 策略用例
     */
    public MemoryPolicyController(MemoryPolicyAppService policies) {
        this.policies = policies;
    }

    /**
     * @param agentId Agent 主键
     * @return 已保存的策略；还没有时是平台默认且 id 为空
     */
    @SaCheckPermission(MemoryPermissions.POLICY_LIST)
    @GetMapping
    public Result<MemoryPolicyResp> get(@RequestParam Long agentId) {
        return Result.ok(toResp(policies.get(agentId)));
    }

    /**
     * @param req 策略
     * @return 保存后的策略
     */
    @SaCheckPermission(MemoryPermissions.POLICY_EDIT)
    @PutMapping
    public Result<MemoryPolicyResp> save(@Valid @RequestBody MemoryPolicyReq req) {
        return Result.ok(toResp(policies.save(toPolicy(req))));
    }

    private static MemoryPolicy toPolicy(MemoryPolicyReq req) {
        MemoryPolicy policy = new MemoryPolicy();
        policy.setAgentId(req.getAgentId());
        policy.setEnableShort(req.getEnableShort());
        policy.setEnableLong(req.getEnableLong());
        policy.setMaxShortItems(req.getMaxShortItems());
        policy.setMaxLongItems(req.getMaxLongItems());
        policy.setCtxMaxTurns(req.getCtxMaxTurns());
        policy.setCtxMaxChars(req.getCtxMaxChars());
        policy.setCtxIdleTtlSec(req.getCtxIdleTtlSec());
        policy.setShortDefaultTtlSec(req.getShortDefaultTtlSec());
        policy.setSummaryModelInstanceId(req.getSummaryModelInstanceId());
        policy.setEnableLongExtract(req.getEnableLongExtract());
        policy.setLongExtractModelInstanceId(req.getLongExtractModelInstanceId());
        policy.setLongExtractOnSessionEnd(req.getLongExtractOnSessionEnd());
        policy.setLongExtractOnExplicit(req.getLongExtractOnExplicit());
        policy.setRemark(req.getRemark());
        return policy;
    }

    private static MemoryPolicyResp toResp(MemoryPolicy policy) {
        MemoryPolicyResp resp = new MemoryPolicyResp();
        resp.setId(policy.getId());
        resp.setAgentId(policy.getAgentId());
        resp.setEnableShort(policy.getEnableShort());
        resp.setEnableLong(policy.getEnableLong());
        resp.setMaxShortItems(policy.getMaxShortItems());
        resp.setMaxLongItems(policy.getMaxLongItems());
        resp.setCtxMaxTurns(policy.getCtxMaxTurns());
        resp.setCtxMaxChars(policy.getCtxMaxChars());
        resp.setCtxIdleTtlSec(policy.getCtxIdleTtlSec());
        resp.setShortDefaultTtlSec(policy.getShortDefaultTtlSec());
        resp.setSummaryModelInstanceId(policy.getSummaryModelInstanceId());
        resp.setEnableLongExtract(policy.getEnableLongExtract());
        resp.setLongExtractModelInstanceId(policy.getLongExtractModelInstanceId());
        resp.setLongExtractOnSessionEnd(policy.getLongExtractOnSessionEnd());
        resp.setLongExtractOnExplicit(policy.getLongExtractOnExplicit());
        resp.setStatus(policy.getStatus());
        resp.setRemark(policy.getRemark());
        return resp;
    }
}
