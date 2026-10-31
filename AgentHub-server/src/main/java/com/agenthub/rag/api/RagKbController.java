package com.agenthub.rag.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.api.Result;
import com.agenthub.rag.api.dto.RagKbCreateReq;
import com.agenthub.rag.api.dto.RagKbPageReq;
import com.agenthub.rag.api.dto.RagKbResp;
import com.agenthub.rag.api.dto.RagKbUpdateReq;
import com.agenthub.rag.application.RagKnowledgeBaseAppService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库接口。所属域：rag。
 * 只暴露「知识库」语义：Collection 名、模型 Key、维度来源都不出现在响应里。
 * 校验、唯一性和审计都在应用服务，Controller 零业务。
 */
@RestController
@RequestMapping("/api/rag/kbs")
public class RagKbController {

    private final RagKnowledgeBaseAppService kbs;

    /**
     * @param kbs 知识库用例
     */
    public RagKbController(RagKnowledgeBaseAppService kbs) {
        this.kbs = kbs;
    }

    /**
     * @param req 分页；可选 keyword / status / visibility
     * @return 一页知识库
     */
    @SaCheckPermission(RagPermissions.KB_LIST)
    @GetMapping
    public Result<PageResult<RagKbResp>> page(@Valid RagKbPageReq req) {
        return Result.ok(kbs.page(req));
    }

    /**
     * @param id 主键
     * @return 详情
     */
    @SaCheckPermission(RagPermissions.KB_LIST)
    @GetMapping("/{id}")
    public Result<RagKbResp> get(@PathVariable Long id) {
        return Result.ok(kbs.get(id));
    }

    /**
     * 新建并立即建 Collection。建集失败时仍返回 200，status=INIT_FAILED 且带 errorMsg。
     *
     * @param req 创建参数
     * @return 创建结果详情
     */
    @SaCheckPermission(RagPermissions.KB_ADD)
    @PostMapping
    public Result<RagKbResp> create(@Valid @RequestBody RagKbCreateReq req) {
        return Result.ok(kbs.create(req));
    }

    /**
     * @param id  主键
     * @param req 可改字段
     * @return 更新后的详情
     */
    @SaCheckPermission(RagPermissions.KB_EDIT)
    @PutMapping("/{id}")
    public Result<RagKbResp> update(@PathVariable Long id, @Valid @RequestBody RagKbUpdateReq req) {
        return Result.ok(kbs.update(id, req));
    }

    /**
     * 重建集合：初始化失败恢复，或 Milvus 数据被清理后修复。
     *
     * @param id 主键
     * @return 重建后的详情
     */
    @SaCheckPermission(RagPermissions.KB_REBUILD)
    @PostMapping("/{id}/rebuild")
    public Result<RagKbResp> rebuild(@PathVariable Long id) {
        return Result.ok(kbs.rebuild(id));
    }

    /**
     * 删除知识库：先删向量集合，再逻辑删记录。
     *
     * @param id 主键
     * @return 空数据
     */
    @SaCheckPermission(RagPermissions.KB_REMOVE)
    @DeleteMapping("/{id}")
    public Result<Void> remove(@PathVariable Long id) {
        kbs.remove(id);
        return Result.ok();
    }
}
