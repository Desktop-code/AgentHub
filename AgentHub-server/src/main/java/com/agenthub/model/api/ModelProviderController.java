package com.agenthub.model.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.agenthub.common.api.Result;
import com.agenthub.model.api.dto.CredentialCreateReq;
import com.agenthub.model.api.dto.CredentialResetReq;
import com.agenthub.model.api.dto.CredentialResp;
import com.agenthub.model.api.dto.CredentialUpdateReq;
import com.agenthub.model.api.dto.ProviderCreateReq;
import com.agenthub.model.api.dto.ProviderResp;
import com.agenthub.model.api.dto.ProviderUpdateReq;
import com.agenthub.model.application.ModelCredentialAppService;
import com.agenthub.model.application.ModelProviderAppService;
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
 * 供应商与凭证接口。所属域：model。只做权限和参数校验，加密与探活不在这里。
 */
@RestController
@RequestMapping("/api/model/providers")
public class ModelProviderController {

    private final ModelProviderAppService providers;
    private final ModelCredentialAppService credentials;

    /**
     * @param providers   供应商用例
     * @param credentials 凭证用例
     */
    public ModelProviderController(ModelProviderAppService providers, ModelCredentialAppService credentials) {
        this.providers = providers;
        this.credentials = credentials;
    }

    /**
     * @return 卡片列表，只有掩码
     */
    @SaCheckPermission(ModelPermissions.PROVIDER_LIST)
    @GetMapping
    public Result<List<ProviderResp>> list() {
        return Result.ok(providers.list());
    }

    /**
     * @param id 供应商 id
     * @return 详情和凭证掩码
     */
    @SaCheckPermission(ModelPermissions.PROVIDER_LIST)
    @GetMapping("/{id}")
    public Result<ProviderResp> get(@PathVariable Long id) {
        return Result.ok(providers.get(id));
    }

    /**
     * @param req 供应商和可选的凭证名 + Key
     * @return 详情，凭证只含掩码
     */
    @SaCheckPermission(ModelPermissions.PROVIDER_ADD)
    @PostMapping
    public Result<ProviderResp> create(@Valid @RequestBody ProviderCreateReq req) {
        return Result.ok(providers.create(req));
    }

    /**
     * @param id  供应商 id
     * @param req 不含 Key 的资料
     * @return 更新后的详情
     */
    @SaCheckPermission(ModelPermissions.PROVIDER_EDIT)
    @PutMapping("/{id}")
    public Result<ProviderResp> update(@PathVariable Long id, @Valid @RequestBody ProviderUpdateReq req) {
        return Result.ok(providers.update(id, req));
    }

    /**
     * @param id 供应商 id
     * @return 空数据
     */
    @SaCheckPermission(ModelPermissions.PROVIDER_REMOVE)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        providers.delete(id);
        return Result.ok();
    }

    /**
     * @param id  供应商 id
     * @param req 新凭证
     * @return 只含掩码的凭证
     */
    @SaCheckPermission(ModelPermissions.PROVIDER_EDIT)
    @PostMapping("/{id}/credentials")
    public Result<CredentialResp> addCredential(@PathVariable Long id, @Valid @RequestBody CredentialCreateReq req) {
        return Result.ok(credentials.create(id, req));
    }

    /**
     * @param id     供应商 id
     * @param credId 凭证 id
     * @param req    名称和状态
     * @return 不含明文
     */
    @SaCheckPermission(ModelPermissions.PROVIDER_EDIT)
    @PutMapping("/{id}/credentials/{credId}")
    public Result<CredentialResp> updateCredential(@PathVariable Long id, @PathVariable Long credId,
                                                   @Valid @RequestBody CredentialUpdateReq req) {
        return Result.ok(credentials.update(id, credId, req));
    }

    /**
     * 编辑凭证或删除供应商的权限都可以删一条未被引用的凭证。
     *
     * @param id     供应商 id
     * @param credId 凭证 id
     * @return 空数据
     */
    @SaCheckPermission(value = {ModelPermissions.PROVIDER_EDIT, ModelPermissions.PROVIDER_REMOVE}, mode = SaMode.OR)
    @DeleteMapping("/{id}/credentials/{credId}")
    public Result<Void> deleteCredential(@PathVariable Long id, @PathVariable Long credId) {
        credentials.delete(id, credId);
        return Result.ok();
    }

    /**
     * @param id     供应商 id
     * @param credId 凭证 id
     * @param req    新 Key
     * @return 只含新掩码的凭证
     */
    @SaCheckPermission(ModelPermissions.PROVIDER_RESET_KEY)
    @PostMapping("/{id}/credentials/{credId}/reset-key")
    public Result<CredentialResp> resetKey(@PathVariable Long id, @PathVariable Long credId,
                                           @Valid @RequestBody CredentialResetReq req) {
        return Result.ok(credentials.resetKey(id, credId, req));
    }
}
