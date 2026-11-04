package com.agenthub.eval.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.api.Result;
import com.agenthub.eval.api.dto.EvalCaseResp;
import com.agenthub.eval.api.dto.EvalImportResp;
import com.agenthub.eval.api.dto.EvalSuiteCreateReq;
import com.agenthub.eval.api.dto.EvalSuiteDetailResp;
import com.agenthub.eval.api.dto.EvalSuitePageReq;
import com.agenthub.eval.api.dto.EvalSuiteResp;
import com.agenthub.eval.api.dto.EvalSuiteUpdateReq;
import com.agenthub.eval.application.CaseStoreAppService;
import com.agenthub.eval.domain.model.EvalCaseSpec;
import com.agenthub.eval.domain.model.EvalSuiteSpec;
import com.agenthub.eval.domain.service.EvalSpecs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 评测用例库接口。所属域：eval。
 * <p>本刀只有 suite / case，没有 runs（跑批属刀2）。控制器零业务：校验、版本、租户都在
 * {@link CaseStoreAppService}。
 * <p>路径与定稿 §5.4 对齐：{@code /api/eval/suites}。
 * <p>权限：查看 {@code eval:suite:view}，写 {@code eval:suite:edit}（见 {@link EvalPermissions}）。
 */
@RestController
@RequestMapping("/api/eval/suites")
public class EvalSuiteController {

    private final CaseStoreAppService store;
    private final ObjectMapper json;

    /**
     * @param store 用例库用例
     * @param json  与样例导入共用的 ObjectMapper（含 enabled 宽松反序列化）
     */
    public EvalSuiteController(CaseStoreAppService store, ObjectMapper json) {
        this.store = store;
        this.json = json;
    }

    /**
     * 套件列表；默认并集平台只读样例。
     *
     * @param req 分页与筛选
     * @return 套件页
     */
    @SaCheckPermission(EvalPermissions.SUITE_VIEW)
    @GetMapping
    public Result<PageResult<EvalSuiteResp>> page(@Valid EvalSuitePageReq req) {
        return Result.ok(store.page(req));
    }

    /**
     * 把 classpath 平台样例装进当前租户；同名键已存在则升版本。
     *
     * @return 导入结果
     */
    @SaCheckPermission(EvalPermissions.SUITE_EDIT)
    @PostMapping("/import-sample")
    public Result<EvalImportResp> importSample() {
        return Result.ok(store.importPlatformSample());
    }

    /**
     * 套件详情，含当前版本用例。
     *
     * @param id 主键
     * @return 元数据 + 用例
     */
    @SaCheckPermission(EvalPermissions.SUITE_VIEW)
    @GetMapping("/{id:\\d+}")
    public Result<EvalSuiteDetailResp> get(@PathVariable Long id) {
        return Result.ok(store.get(id));
    }

    /**
     * 新建套件，可带初始 cases。
     *
     * @param req 新套件
     * @return 详情
     */
    @SaCheckPermission(EvalPermissions.SUITE_EDIT)
    @PostMapping
    public Result<EvalSuiteDetailResp> create(@Valid @RequestBody EvalSuiteCreateReq req) {
        return Result.ok(store.create(req));
    }

    /**
     * 更新套件元数据（名称/描述/默认配置/启停）；**不改 cases**。
     *
     * @param id  主键
     * @param req 新元数据
     * @return 更新后的详情
     */
    @SaCheckPermission(EvalPermissions.SUITE_EDIT)
    @PutMapping("/{id:\\d+}")
    public Result<EvalSuiteDetailResp> update(@PathVariable Long id, @Valid @RequestBody EvalSuiteUpdateReq req) {
        return Result.ok(store.update(id, req));
    }

    /**
     * 导入完整套件 JSON，写入新版本并推进 current_version。
     * <p>请求体走 {@link EvalSpecs#fromTree}，与 classpath 样例同一套解析：
     * snake/camel 双认；{@code enabled} 认 0/1 与 true/false；真正坏 JSON 才报格式错。
     *
     * @param id   主键
     * @param body 套件 JSON 树（suite_key/name 可省略，沿用目标套件）
     * @return 新旧版本号与新版本用例数
     */
    @SaCheckPermission(EvalPermissions.SUITE_EDIT)
    @PostMapping("/{id:\\d+}/import")
    public Result<EvalImportResp> importSuite(@PathVariable Long id, @RequestBody JsonNode body) {
        EvalSuiteSpec spec = EvalSpecs.fromTree(body, json);
        return Result.ok(store.importSuite(id, spec));
    }

    /**
     * 按版本列出用例；不传版本取当前版本。
     *
     * @param id           套件主键
     * @param suiteVersion 版本，可空
     * @return 用例列表
     */
    @SaCheckPermission(EvalPermissions.SUITE_VIEW)
    @GetMapping("/{id:\\d+}/cases")
    public Result<List<EvalCaseResp>> cases(@PathVariable Long id,
                                            @RequestParam(required = false) Integer suiteVersion) {
        return Result.ok(store.listCases(id, suiteVersion));
    }

    /**
     * 写单条用例（新增或替换），服务端升版本。
     *
     * @param id  套件主键
     * @param req 用例正文（与导入里的 case 结构完全一致）
     * @return 新版本号与该版本用例数
     */
    @SaCheckPermission(EvalPermissions.SUITE_EDIT)
    @PostMapping("/{id:\\d+}/cases")
    public Result<EvalImportResp> upsertCase(@PathVariable Long id, @Valid @RequestBody EvalCaseSpec req) {
        return Result.ok(store.upsertCase(id, req));
    }

    /**
     * 删除单条用例，服务端升版本（历史版本保留）。
     *
     * @param id      套件主键
     * @param caseKey 用例键
     * @return 新版本号与该版本剩余用例数
     */
    @SaCheckPermission(EvalPermissions.SUITE_EDIT)
    @DeleteMapping("/{id:\\d+}/cases/{caseKey}")
    public Result<EvalImportResp> deleteCase(@PathVariable Long id, @PathVariable String caseKey) {
        return Result.ok(store.deleteCase(id, caseKey));
    }

    /**
     * 逻辑删除套件及其全部版本用例。
     *
     * @param id 主键
     * @return 空数据
     */
    @SaCheckPermission(EvalPermissions.SUITE_EDIT)
    @DeleteMapping("/{id:\\d+}")
    public Result<Void> remove(@PathVariable Long id) {
        store.remove(id);
        return Result.ok();
    }
}
