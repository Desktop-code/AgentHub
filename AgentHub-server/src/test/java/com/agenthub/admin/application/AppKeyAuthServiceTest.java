package com.agenthub.admin.application;

import com.agenthub.admin.domain.model.AppCredential;
import com.agenthub.admin.domain.model.AppIdentity;
import com.agenthub.admin.domain.repository.AppRepository;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.security.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 入站应用鉴权单测。所属域：admin 的 application。
 * <p>覆盖：正确密钥通过、错 secret / 未知 key / 缺头统一 401、停用应用 403。
 * <p>用假 PasswordHasher（前缀比较）避免 BCrypt 成本；被测逻辑是分支与文案，不是哈希实现。
 */
class AppKeyAuthServiceTest {

    private static final String APP_KEY = "app_test01";

    private AppRepository apps;
    private AppKeyAuthService service;

    @BeforeEach
    void setUp() {
        apps = mock(AppRepository.class);
        // 假哈希：hash = "bcrypt:" + raw；matches 只做字符串比较，不引入 BCrypt 开销
        PasswordHasher hasher = new PasswordHasher() {
            @Override
            public String hash(String raw) {
                return "bcrypt:" + raw;
            }

            @Override
            public boolean matches(String raw, String hash) {
                return hash != null && hash.equals("bcrypt:" + raw);
            }
        };
        service = new AppKeyAuthService(apps, hasher);
    }

    @Test
    @DisplayName("正确 app_key + secret：返回应用身份，不含密钥")
    void authenticateOk() {
        when(apps.findCredentialByAppKey(APP_KEY)).thenReturn(Optional.of(credential(1)));

        AppIdentity identity = service.authenticate(APP_KEY, "s3cret");

        assertThat(identity.getAppId()).isEqualTo(100L);
        assertThat(identity.getAppKey()).isEqualTo(APP_KEY);
        assertThat(identity.getDeptId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("错误 secret：401，文案不透露 key 是否存在")
    void wrongSecretRejected() {
        when(apps.findCredentialByAppKey(APP_KEY)).thenReturn(Optional.of(credential(1)));

        assertThatThrownBy(() -> service.authenticate(APP_KEY, "wrong"))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED)
                .hasMessage("应用未授权或密钥错误");
    }

    @Test
    @DisplayName("未知 app_key：与错 secret 同一文案")
    void unknownKeyRejected() {
        when(apps.findCredentialByAppKey("app_missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.authenticate("app_missing", "s3cret"))
                .isInstanceOf(BizException.class)
                .hasMessage("应用未授权或密钥错误");
    }

    @Test
    @DisplayName("缺少请求头：直接 401，不查库")
    void missingHeaderRejected() {
        assertThatThrownBy(() -> service.authenticate(" ", null))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("应用停用：403 且给出可处置文案")
    void disabledAppRejected() {
        when(apps.findCredentialByAppKey(APP_KEY)).thenReturn(Optional.of(credential(0)));

        assertThatThrownBy(() -> service.authenticate(APP_KEY, "s3cret"))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN)
                .hasMessageContaining("应用已停用");
    }

    private AppCredential credential(int status) {
        AppCredential credential = new AppCredential();
        credential.setAppId(100L);
        credential.setAppKey(APP_KEY);
        credential.setName("订单系统");
        credential.setDeptId(5L);
        credential.setStatus(status);
        credential.setSecretHash("bcrypt:s3cret");
        return credential;
    }
}
