package com.agenthub.rag.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.common.api.Result;
import com.agenthub.rag.api.dto.AgentRerankResp;
import com.agenthub.rag.api.dto.AgentRerankUpdateReq;
import com.agenthub.rag.application.AgentRetrievalConfigAppService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 检索链路重排配置接口。所属域：rag。
 * <p>
 * 路由挂在 Agent 的「知识库」语义下，权限沿用 {@link RagPermissions}：
 * 读用 {@code rag:kb:list}，写用 {@code rag:kb:edit}，与知识库绑定完全一致——
 * 重排是检索链路的一部分，不该再拆一个新的权限码。
 * <p>
 * 注意：模型配置的 {@code /api/agent/agents/{id}/model-binds} 已不再提供 RERANK 写入口，
 * 本组接口是该配置的唯一入口。
 */
@RestController
@RequestMapping("/api/rag/agents/{agentId}/retrieval")
public class AgentRetrievalConfigController {

    private final AgentRetrievalConfigAppService service;

    /**
     * @param service 重排配置用例
     */
    public AgentRetrievalConfigController(AgentRetrievalConfigAppService service) {
        this.service = service;
    }

    /**
     * 读当前重排配置。
     *
     * @param agentId Agent 主键
     * @return 重排配置；未配置时字段为空
     */
    @SaCheckPermission(RagPermissions.KB_LIST)
    @GetMapping("/rerank")
    public Result<AgentRerankResp> read(@PathVariable Long agentId) {
        return Result.ok(service.read(agentId));
    }

    /**
     * 设置或清空重排模型（modelInstanceId 传 0 表示关闭）。
     *
     * @param agentId Agent 主键
     * @param req     目标 RERANK 实例
     * @return 保存后的配置
     */
    @SaCheckPermission(RagPermissions.KB_EDIT)
    @PutMapping("/rerank")
    public Result<AgentRerankResp> save(@PathVariable Long agentId, @Valid @RequestBody AgentRerankUpdateReq req) {
        return Result.ok(service.save(agentId, req.getModelInstanceId()));
    }
}
