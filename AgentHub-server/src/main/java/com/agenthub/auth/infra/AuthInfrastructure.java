package com.agenthub.auth.infra;

import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.agenthub.admin.domain.model.User;
import com.agenthub.admin.domain.repository.UserRepository;
import com.agenthub.auth.domain.service.AccessSnapshotProvider;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * Sa-Token 权限加载，以及当前操作者。所属域：auth。
 * 权限本身按用户维度缓存在 Redis（见 {@code CachedAccessSnapshotProvider}）：
 * 角色/菜单变更由写入口在事务提交后推进版本，因此「改完即生效」的语义保持不变；
 * 用户行仍每次按主键读库，停用用户的权限能当场归零，不依赖缓存过期。
 */
public final class AuthInfrastructure {

    private AuthInfrastructure() {
    }

    /**
     * 给 @SaCheckPermission 提供角色和权限码。
     */
    @Component
    public static class StpInterfaceImpl implements StpInterface {

        private final UserRepository users;
        private final AccessSnapshotProvider accessSnapshots;

        /**
         * @param users           按登录 id 找用户（每次按主键读库，停用能当场归零）
         * @param accessSnapshots 权限与角色（生产走缓存装饰器）
         */
        public StpInterfaceImpl(UserRepository users, AccessSnapshotProvider accessSnapshots) {
            this.users = users;
            this.accessSnapshots = accessSnapshots;
        }

        /**
         * @param loginId   平台用户 id
         * @param loginType 登录类型，本期忽略
         * @return 权限码；用户不存在或已停用时为空，后续注解会得到 403
         */
        @Override
        public List<String> getPermissionList(Object loginId, String loginType) {
            User user = load(loginId);
            return user == null ? List.of() : accessSnapshots.snapshot(user).getPermissions();
        }

        /**
         * @param loginId   平台用户 id
         * @param loginType 登录类型，本期忽略
         * @return 启用角色的编码
         */
        @Override
        public List<String> getRoleList(Object loginId, String loginType) {
            User user = load(loginId);
            return user == null ? List.of() : accessSnapshots.snapshot(user).getRoles();
        }

        private User load(Object loginId) {
            if (loginId == null) {
                return null;
            }
            return users.findById(Long.valueOf(loginId.toString())).orElse(null);
        }
    }

    /**
     * 从 Sa-Token 和当前请求取操作者。未登录不抛异常，交给 requireUserId。
     */
    @Component
    public static class SaOperatorContext implements OperatorContext {

        /**
         * {@inheritDoc}
         * <p>
         * 无 Web 上下文时（如 RAG 入库线程池）Sa-Token 会抛上下文异常；此处吞掉并返回 null，
         * 避免后台任务在「已 markRunning」后因读操作者而静默崩掉。
         */
        @Override
        public Long userIdOrNull() {
            try {
                if (!StpUtil.isLogin()) {
                    return null;
                }
                return StpUtil.getLoginIdAsLong();
            } catch (RuntimeException ex) {
                // 典型：SaTokenContextException「未能获取有效的上下文处理器」
                return null;
            }
        }

        /** {@inheritDoc} */
        @Override
        public long requireUserId() {
            Long userId = userIdOrNull();
            if (userId == null) {
                throw new BizException(ErrorCode.UNAUTHORIZED);
            }
            return userId;
        }

        /** {@inheritDoc} */
        @Override
        public String usernameOrNull() {
            try {
                if (!StpUtil.isLogin()) {
                    return null;
                }
                Object name = StpUtil.getSession().get("username");
                return name == null ? null : name.toString();
            } catch (RuntimeException ex) {
                return null;
            }
        }

        /** {@inheritDoc} */
        @Override
        public String clientIp() {
            HttpServletRequest request = request();
            if (request == null) {
                return "";
            }
            // 只取第一段，避免把整串代理头写进日志
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
            return request.getRemoteAddr() == null ? "" : request.getRemoteAddr();
        }

        /** {@inheritDoc} */
        @Override
        public String userAgent() {
            HttpServletRequest request = request();
            if (request == null || request.getHeader("User-Agent") == null) {
                return "";
            }
            return request.getHeader("User-Agent");
        }

        /** {@inheritDoc} */
        @Override
        public String requestMethod() {
            HttpServletRequest request = request();
            return request == null ? null : request.getMethod();
        }

        /** {@inheritDoc} */
        @Override
        public String requestUri() {
            HttpServletRequest request = request();
            return request == null ? null : request.getRequestURI();
        }

        private HttpServletRequest request() {
            if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
                return null;
            }
            return attributes.getRequest();
        }
    }
}
