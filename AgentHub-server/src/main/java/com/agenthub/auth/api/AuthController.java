package com.agenthub.auth.api;

import com.agenthub.auth.api.dto.LoginReq;
import com.agenthub.auth.api.dto.LoginResp;
import com.agenthub.auth.api.dto.MeResp;
import com.agenthub.auth.application.AuthAppService;
import com.agenthub.common.api.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录会话接口。所属域：auth。不含权限判断以外的业务，登录本身匿名。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthAppService authAppService;

    /**
     * @param authAppService 登录用例
     */
    public AuthController(AuthAppService authAppService) {
        this.authAppService = authAppService;
    }

    /**
     * @param req 用户名和密码
     * @return 裸 token
     */
    @PostMapping("/login")
    public Result<LoginResp> login(@Valid @RequestBody LoginReq req) {
        return Result.ok(authAppService.login(req));
    }

    /**
     * 注销当前 Token。
     *
     * @return 空数据
     */
    @PostMapping("/logout")
    public Result<Void> logout() {
        authAppService.logout();
        return Result.ok();
    }

    /**
     * @return 当前用户、权限和菜单树
     */
    @GetMapping("/me")
    public Result<MeResp> me() {
        return Result.ok(authAppService.me());
    }
}
