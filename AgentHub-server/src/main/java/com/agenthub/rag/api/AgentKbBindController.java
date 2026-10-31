package com.agenthub.rag.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.common.api.Result;
import com.agenthub.rag.api.dto.KbBindCreateReq;
import com.agenthub.rag.api.dto.KbBindResp;
import com.agenthub.rag.api.dto.KbBindUpdateReq;
import com.agenthub.rag.application.AgentKbBindAppService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent 知识库绑定接口。所属域：rag。④ 期。
 * 挂在 Agent 路径下，因为绑定归属于某个 Agent；权限码仍用 rag 域，
 * 便于「谁配知识库」与「谁能改 Agent」分开授权。
 */
@RestController
@RequestMapping("/api/rag/agents/{agentId}/kbs")
public class AgentKbBindController {

    private final AgentKbBindAppService binds;

    /**
     * @param binds 绑定用例
     */
    public AgentKbBindController(AgentKbBindAppService binds) {
        this.binds = binds;
    }

    /**
     * @param agentId Agent 主键
     * @return 该 Agent 绑定的知识库
     */
    @SaCheckPermission(RagPermissions.KB_LIST)
    @GetMapping
    public Result<List<KbBindResp>> list(@PathVariable Long agentId) {
        return Result.ok(binds.list(agentId));
    }

    /**
     * 新增绑定。知识库必须对当前用户可见且可用。
     *
     * @param agentId Agent 主键
     * @param req     知识库与覆盖项
     * @return 新绑定
     */
    @SaCheckPermission(RagPermissions.KB_EDIT)
    @PostMapping
    public Result<KbBindResp> create(@PathVariable Long agentId, @Valid @RequestBody KbBindCreateReq req) {
        return Result.ok(binds.create(agentId, req));
    }

    /**
     * 改启停与覆盖项。停用后该库不进运行时检索。
     *
     * @param agentId Agent 主键
     * @param bindId  绑定主键
     * @param req     目标状态与覆盖项
     * @return 更新后的绑定
     */
    @SaCheckPermission(RagPermissions.KB_EDIT)
    @PutMapping("/{bindId}")
    public Result<KbBindResp> update(@PathVariable Long agentId, @PathVariable Long bindId,
                                     @Valid @RequestBody KbBindUpdateReq req) {
        return Result.ok(binds.update(agentId, bindId, req));
    }

    /**
     * @param agentId Agent 主键
     * @param bindId  绑定主键
     * @return 空数据
     */
    @SaCheckPermission(RagPermissions.KB_EDIT)
    @DeleteMapping("/{bindId}")
    public Result<Void> remove(@PathVariable Long agentId, @PathVariable Long bindId) {
        binds.remove(agentId, bindId);
        return Result.ok();
    }
}
