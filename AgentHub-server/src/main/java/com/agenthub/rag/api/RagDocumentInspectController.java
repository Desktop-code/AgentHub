package com.agenthub.rag.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.agenthub.common.api.Result;
import com.agenthub.rag.api.dto.RagChunkResp;
import com.agenthub.rag.api.dto.RagChunkUpdateReq;
import com.agenthub.rag.api.dto.RagParseTextResp;
import com.agenthub.rag.api.dto.RagRechunkPreviewResp;
import com.agenthub.rag.api.dto.RagRechunkReq;
import com.agenthub.rag.application.RagDocumentInspectAppService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文档原文对照、重切预览与单条切片编辑接口。所属域：rag。
 * <p>
 * 与 {@link RagDocumentController} 同属 {@code /api/rag/kbs/{kbId}/documents}：
 * 上传 / 列表 / 重试 / 删除 / 确认重切仍在原控制器，这里只承载「看上一步产物 + 人工微调」。
 * 预览使用只读切片权限；确认覆盖与单条编辑沿用 {@code rag:doc:rechunk}，不新增权限码。
 */
@RestController
@RequestMapping("/api/rag/kbs/{kbId}/documents")
public class RagDocumentInspectController {

    private final RagDocumentInspectAppService inspect;

    /**
     * @param inspect 对照 / 预览 / 单条编辑用例
     */
    public RagDocumentInspectController(RagDocumentInspectAppService inspect) {
        this.inspect = inspect;
    }

    /**
     * 本轮入库实际使用的解析文本。
     * <p>
     * 只读：老文档允许从原文件即时解析，不写产物、不改切片与向量。
     *
     * @param kbId  知识库
     * @param docId 文档
     * @return 解析文本或不可用原因
     */
    @SaCheckPermission(value = {RagPermissions.DOC_LIST, RagPermissions.DOC_CHUNK}, mode = SaMode.OR)
    @GetMapping("/{docId}/parse")
    public Result<RagParseTextResp> parseText(@PathVariable Long kbId, @PathVariable Long docId) {
        return Result.ok(inspect.parseText(kbId, docId));
    }

    /**
     * 重新切片预览：只算不写，返回预计片数与新切片卡片。
     * <p>
     * 权限沿用只读切片权限；真正的覆盖动作仍走原 {@code POST /rechunk}（需 {@code rag:doc:rechunk}）。
     *
     * @param kbId  知识库
     * @param docId 文档
     * @param req   片长与重叠
     * @return 预览结果
     */
    @SaCheckPermission(value = {RagPermissions.DOC_LIST, RagPermissions.DOC_CHUNK}, mode = SaMode.OR)
    @PostMapping("/{docId}/rechunk/preview")
    public Result<RagRechunkPreviewResp> preview(@PathVariable Long kbId, @PathVariable Long docId,
                                                 @Valid @RequestBody RagRechunkReq req) {
        return Result.ok(inspect.preview(kbId, docId, req));
    }

    /**
     * 单条切片编辑：只更新该片正文 / 章节路径并重写对应向量。
     *
     * @param kbId    知识库
     * @param docId   文档
     * @param chunkId 切片
     * @param req     新正文与可选章节路径
     * @return 更新后的切片
     */
    @SaCheckPermission(RagPermissions.DOC_RECHUNK)
    @PutMapping("/{docId}/chunks/{chunkId}")
    public Result<RagChunkResp> updateChunk(@PathVariable Long kbId, @PathVariable Long docId,
                                            @PathVariable Long chunkId,
                                            @Valid @RequestBody RagChunkUpdateReq req) {
        return Result.ok(inspect.updateChunk(kbId, docId, chunkId, req));
    }
}
