package com.agenthub.audit.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.admin.domain.enums.PermissionCodes;
import com.agenthub.audit.domain.model.LoginLog;
import com.agenthub.audit.domain.model.LoginLogQuery;
import com.agenthub.audit.domain.model.OperLog;
import com.agenthub.audit.domain.model.OperLogQuery;
import com.agenthub.audit.domain.repository.LoginLogRepository;
import com.agenthub.audit.domain.repository.OperLogRepository;
import com.agenthub.common.api.PageQuery;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.api.Result;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 审计只读接口。所属域：audit。日志按权限码开放，本期不做部门裁剪。
 */
public final class AuditApi {

    private AuditApi() {
    }

    /**
     * 登录日志分页条件。
     */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class LoginLogPageReq extends PageQuery {
        private String username;
        private Integer success;
    }

    /**
     * 登录日志行。
     */
    @Data
    public static class LoginLogResp {
        private Long id;
        private Long userId;
        private String username;
        private Integer loginType;
        private String ip;
        private Integer success;
        private String message;
        private LocalDateTime createTime;
    }

    /**
     * 操作日志分页条件。
     */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class OperLogPageReq extends PageQuery {
        private String module;
        private String username;
    }

    /**
     * 操作日志行。不返回 detail 以外的敏感正文；detail 本身只是摘要。
     */
    @Data
    public static class OperLogResp {
        private Long id;
        private Long userId;
        private String username;
        private String module;
        private String action;
        private String targetType;
        private String targetId;
        private String requestMethod;
        private String requestUrl;
        private String ip;
        private Integer success;
        private String detailJson;
        private LocalDateTime createTime;
    }

    /**
     * 登录日志查询。
     */
    @RestController
    @RequestMapping("/api/system/login-logs")
    public static class LoginLogController {

        private final LoginLogRepository repository;

        /**
         * @param repository 登录日志仓储
         */
        public LoginLogController(LoginLogRepository repository) {
            this.repository = repository;
        }

        /**
         * @param req 分页条件
         * @return 登录日志
         */
        @SaCheckPermission(PermissionCodes.LOG_LOGIN)
        @GetMapping
        public Result<PageResult<LoginLogResp>> page(@Valid LoginLogPageReq req) {
            LoginLogQuery query = new LoginLogQuery();
            query.setCurrent(req.getCurrent());
            query.setSize(req.getSize());
            query.setUsername(req.getUsername());
            query.setSuccess(req.getSuccess());
            PageResult<LoginLog> page = repository.page(query);
            return Result.ok(PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                    page.getRecords().stream().map(LoginLogController::toResp).toList()));
        }

        private static LoginLogResp toResp(LoginLog log) {
            LoginLogResp resp = new LoginLogResp();
            resp.setId(log.getId());
            resp.setUserId(log.getUserId());
            resp.setUsername(log.getUsername());
            resp.setLoginType(log.getLoginType());
            resp.setIp(log.getIp());
            resp.setSuccess(log.getSuccess());
            resp.setMessage(log.getMessage());
            resp.setCreateTime(log.getCreateTime());
            return resp;
        }
    }

    /**
     * 操作日志查询。
     */
    @RestController
    @RequestMapping("/api/system/oper-logs")
    public static class OperLogController {

        private final OperLogRepository repository;

        /**
         * @param repository 操作日志仓储
         */
        public OperLogController(OperLogRepository repository) {
            this.repository = repository;
        }

        /**
         * @param req 分页条件
         * @return 操作日志
         */
        @SaCheckPermission(PermissionCodes.LOG_OPER)
        @GetMapping
        public Result<PageResult<OperLogResp>> page(@Valid OperLogPageReq req) {
            OperLogQuery query = new OperLogQuery();
            query.setCurrent(req.getCurrent());
            query.setSize(req.getSize());
            query.setModule(req.getModule());
            query.setUsername(req.getUsername());
            PageResult<OperLog> page = repository.page(query);
            return Result.ok(PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                    page.getRecords().stream().map(OperLogController::toResp).toList()));
        }

        private static OperLogResp toResp(OperLog log) {
            OperLogResp resp = new OperLogResp();
            resp.setId(log.getId());
            resp.setUserId(log.getUserId());
            resp.setUsername(log.getUsername());
            resp.setModule(log.getModule());
            resp.setAction(log.getAction());
            resp.setTargetType(log.getTargetType());
            resp.setTargetId(log.getTargetId());
            resp.setRequestMethod(log.getRequestMethod());
            resp.setRequestUrl(log.getRequestUrl());
            resp.setIp(log.getIp());
            resp.setSuccess(log.getSuccess());
            resp.setDetailJson(log.getDetailJson());
            resp.setCreateTime(log.getCreateTime());
            return resp;
        }
    }
}
