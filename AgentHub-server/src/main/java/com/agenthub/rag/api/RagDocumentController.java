package com.agenthub.rag.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.api.Result;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.rag.api.dto.RagChunkResp;
import com.agenthub.rag.api.dto.RagDocumentPageReq;
import com.agenthub.rag.api.dto.RagDocumentResp;
import com.agenthub.rag.api.dto.RagRechunkReq;
import com.agenthub.rag.application.RagDocumentAppService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 知识库文档接口。所属域：rag。② 期。
 * <p>
 * 上传返回的是「已受理」的文档（status=PENDING），真正的解析/向量化在后台线程跑；
 * 前端靠列表轮询看 status / stage / progress 推进。这样大文件不会占住 HTTP 线程。
 */
@RestController
@RequestMapping("/api/rag/kbs/{kbId}/documents")
public class RagDocumentController {

    private final RagDocumentAppService documents;

    /**
     * @param documents 文档用例
     */
    public RagDocumentController(RagDocumentAppService documents) {
        this.documents = documents;
    }

    /**
     * @param kbId 知识库
     * @param req  分页与标题关键字
     * @return 一页文档，含任务进度
     */
    @SaCheckPermission(RagPermissions.DOC_LIST)
    @GetMapping
    public Result<PageResult<RagDocumentResp>> page(@PathVariable Long kbId, @Valid RagDocumentPageReq req) {
        return Result.ok(documents.page(kbId, req.getKeyword(), req.getCurrent(), req.getSize()));
    }

    /**
     * 上传并异步入库。同名文档视为重灌。
     *
     * @param kbId 知识库
     * @param file 文件（txt / md / pdf，≤20MB）
     * @return 已受理的文档
     */
    @SaCheckPermission(RagPermissions.DOC_UPLOAD)
    @PostMapping
    public Result<RagDocumentResp> upload(@PathVariable Long kbId,
                                          @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "上传文件不能为空");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "读取上传文件失败");
        }
        String name = file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename();
        return Result.ok(documents.submit(kbId, name, bytes));
    }

    /**
     * 重试失败任务。
     *
     * @param kbId  知识库
     * @param docId 文档
     * @return 重置后的文档
     */
    @SaCheckPermission(RagPermissions.DOC_RETRY)
    @PostMapping("/{docId}/retry")
    public Result<RagDocumentResp> retry(@PathVariable Long kbId, @PathVariable Long docId) {
        return Result.ok(documents.retry(kbId, docId));
    }

    /**
     * 查看该文档的全部切片（按序号升序）。
     * <p>
     * 权限取 OR：已进文档页的 {@code rag:doc:list} 即可只读预览；
     * 单独授予 {@code rag:doc:chunk} 的角色同样可进。避免种子未灌时列表能开、点选却 403。
     *
     * @param kbId  知识库
     * @param docId 文档
     * @return 切片列表
     */
    @SaCheckPermission(value = {RagPermissions.DOC_LIST, RagPermissions.DOC_CHUNK}, mode = SaMode.OR)
    @GetMapping("/{docId}/chunks")
    public Result<List<RagChunkResp>> chunks(@PathVariable Long kbId, @PathVariable Long docId) {
        return Result.ok(documents.listChunks(kbId, docId));
    }

    /**
     * 按新的片长重新切片并重跑入库。返回「已受理」，进度靠列表轮询。
     *
     * @param kbId  知识库
     * @param docId 文档
     * @param req   片长与重叠
     * @return 重置为 PENDING 的文档
     */
    @SaCheckPermission(RagPermissions.DOC_RECHUNK)
    @PostMapping("/{docId}/rechunk")
    public Result<RagDocumentResp> rechunk(@PathVariable Long kbId, @PathVariable Long docId,
                                           @Valid @RequestBody RagRechunkReq req) {
        return Result.ok(documents.rechunk(kbId, docId, req));
    }

    /**
     * 删除文档：级联清向量、切片、任务与原文件。
     *
     * @param kbId  知识库
     * @param docId 文档
     * @return 空数据
     */
    @SaCheckPermission(RagPermissions.DOC_REMOVE)
    @DeleteMapping("/{docId}")
    public Result<Void> remove(@PathVariable Long kbId, @PathVariable Long docId) {
        documents.remove(kbId, docId);
        return Result.ok();
    }
}
