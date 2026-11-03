package com.agenthub.admin.application;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.admin.domain.model.AppCredential;
import com.agenthub.admin.domain.model.AppIdentity;
import com.agenthub.admin.domain.repository.AppRepository;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.security.PasswordHasher;
import org.springframework.stereotype.Service;

/**
 * 入站应用鉴权。所属域：admin。
 * <p>用途：OpenAPI（{@code /api/open/**}）用 {@code X-App-Key} + {@code X-App-Secret} 换应用身份，
 * 与 Sa-Token 用户会话完全分流；本类不读写 Sa-Token，也不依赖登录用户。
 * <p>密钥纪律：明文只存在于本次方法栈；比对走 {@link PasswordHasher}（与 sys_app 现网同一套 BCrypt），
 * 不进日志、不进响应、不进操作明细。
 * <p>防探测：app_key 不存在与 secret 错误返回<b>同一条</b>文案，避免枚举有效 key。
 * <p>线程：无状态，可复用。
 */
@Service
public class AppKeyAuthService {

    /** 统一失败文案：不区分「key 不存在」与「secret 错误」 */
    private static final String BAD_CREDENTIALS = "应用未授权或密钥错误";

    private final AppRepository apps;
    private final PasswordHasher passwordHasher;

    /**
     * @param apps           应用仓储（只用到凭据查询）
     * @param passwordHasher 与 sys_app 一致的 BCrypt
     */
    public AppKeyAuthService(AppRepository apps, PasswordHasher passwordHasher) {
        this.apps = apps;
        this.passwordHasher = passwordHasher;
    }

    /**
     * 校验 app_key + secret。
     *
     * @param appKey    请求头 X-App-Key
     * @param appSecret 请求头 X-App-Secret（明文，仅本次传输 / 比对）
     * @return 应用身份（不含任何密钥）
     * @throws BizException UNAUTHORIZED 缺参 / 未知 key / 密钥错误；FORBIDDEN 应用已停用
     */
    public AppIdentity authenticate(String appKey, String appSecret) {
        // 缺参不细分成「缺 key」还是「缺 secret」，避免把请求头约定暴露成探测接口
        if (appKey == null || appKey.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, BAD_CREDENTIALS);
        }
        AppCredential credential = apps.findCredentialByAppKey(appKey.trim())
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED, BAD_CREDENTIALS));
        // 停用与密钥错误分开：这是运营可处置的状态，且不会泄露 key 是否存在（已确认存在）
        if (!RecordStatus.isEnabled(credential.getStatus())) {
            throw new BizException(ErrorCode.FORBIDDEN, "应用已停用，请联系平台管理员");
        }
        // 哈希损坏时 PasswordHasher 返回 false，按密钥错误处理，不抛 500
        if (!passwordHasher.matches(appSecret, credential.getSecretHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, BAD_CREDENTIALS);
        }
        return new AppIdentity(credential.getAppId(), credential.getAppKey(),
                credential.getName(), credential.getDeptId());
    }
}
