package com.agenthub.audit.infra.persistence;

import com.agenthub.admin.domain.enums.LoginType;
import com.agenthub.admin.domain.repository.UserRepository;
import com.agenthub.audit.domain.LoginLogWriter;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.audit.domain.model.LoginLog;
import com.agenthub.audit.domain.model.LoginLogQuery;
import com.agenthub.audit.domain.model.OperLog;
import com.agenthub.audit.domain.model.OperLogQuery;
import com.agenthub.audit.domain.repository.LoginLogRepository;
import com.agenthub.audit.domain.repository.OperLogRepository;
import com.agenthub.audit.infra.persistence.entity.SysLoginLogEntity;
import com.agenthub.audit.infra.persistence.entity.SysOperLogEntity;
import com.agenthub.audit.infra.persistence.mapper.LoginLogMapper;
import com.agenthub.audit.infra.persistence.mapper.OperLogMapper;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.web.OperatorContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 审计仓储与写入实现。所属域：audit。
 * 登录日志独立事务；操作日志加入当前业务事务，业务回滚时日志一起回滚。
 */
public final class AuditPersistence {

    private AuditPersistence() {
    }

    /**
     * 登录日志仓储实现。
     */
    @Repository
    public static class LoginLogRepositoryImpl implements LoginLogRepository {

        private final LoginLogMapper mapper;

        /**
         * @param mapper 登录日志表
         */
        public LoginLogRepositoryImpl(LoginLogMapper mapper) {
            this.mapper = mapper;
        }

        /** {@inheritDoc} */
        @Override
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void insert(LoginLog log) {
            SysLoginLogEntity entity = new SysLoginLogEntity();
            entity.setUserId(log.getUserId());
            entity.setUsername(cut(log.getUsername(), 64));
            entity.setLoginType(log.getLoginType() == null ? LoginType.PASSWORD.getCode() : log.getLoginType());
            entity.setIp(cut(log.getIp(), 64));
            entity.setUserAgent(cut(log.getUserAgent(), 512));
            entity.setSuccess(log.getSuccess());
            entity.setMessage(cut(log.getMessage(), 255));
            mapper.insert(entity);
        }

        /** {@inheritDoc} */
        @Override
        public PageResult<LoginLog> page(LoginLogQuery query) {
            long current = Math.max(query.getCurrent(), 1);
            long size = Math.min(Math.max(query.getSize(), 1), 100);
            LambdaQueryWrapper<SysLoginLogEntity> wrapper = new LambdaQueryWrapper<SysLoginLogEntity>()
                    .like(query.getUsername() != null && !query.getUsername().isBlank(), SysLoginLogEntity::getUsername, query.getUsername())
                    .eq(query.getSuccess() != null, SysLoginLogEntity::getSuccess, query.getSuccess())
                    .orderByDesc(SysLoginLogEntity::getId);
            Page<SysLoginLogEntity> page = mapper.selectPage(new Page<>(current, size), wrapper);
            return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                    page.getRecords().stream().map(LoginLogRepositoryImpl::toDomain).toList());
        }

        private static LoginLog toDomain(SysLoginLogEntity entity) {
            LoginLog log = new LoginLog();
            log.setId(entity.getId());
            log.setUserId(entity.getUserId());
            log.setUsername(entity.getUsername());
            log.setLoginType(entity.getLoginType());
            log.setIp(entity.getIp());
            log.setUserAgent(entity.getUserAgent());
            log.setSuccess(entity.getSuccess());
            log.setMessage(entity.getMessage());
            // 域模型仍用 createTime；库列已是 create_time
            log.setCreateTime(entity.getCreateTime());
            return log;
        }
    }

    /**
     * 操作日志仓储实现。
     */
    @Repository
    public static class OperLogRepositoryImpl implements OperLogRepository {

        private final OperLogMapper mapper;

        /**
         * @param mapper 操作日志表
         */
        public OperLogRepositoryImpl(OperLogMapper mapper) {
            this.mapper = mapper;
        }

        /** {@inheritDoc} */
        @Override
        public void insert(OperLog log) {
            SysOperLogEntity entity = new SysOperLogEntity();
            entity.setUserId(log.getUserId());
            entity.setUsername(cut(log.getUsername(), 64));
            entity.setDeptId(log.getDeptId());
            entity.setModule(cut(log.getModule(), 64));
            entity.setAction(cut(log.getAction(), 32));
            entity.setTargetType(cut(log.getTargetType(), 64));
            entity.setTargetId(cut(log.getTargetId(), 64));
            entity.setRequestMethod(cut(log.getRequestMethod(), 16));
            entity.setRequestUrl(cut(log.getRequestUrl(), 255));
            entity.setIp(cut(log.getIp(), 64));
            entity.setSuccess(log.getSuccess() == null ? 1 : log.getSuccess());
            entity.setErrorMsg(cut(log.getErrorMsg(), 512));
            entity.setDetailJson(log.getDetailJson());
            mapper.insert(entity);
        }

        /** {@inheritDoc} */
        @Override
        public PageResult<OperLog> page(OperLogQuery query) {
            long current = Math.max(query.getCurrent(), 1);
            long size = Math.min(Math.max(query.getSize(), 1), 100);
            LambdaQueryWrapper<SysOperLogEntity> wrapper = new LambdaQueryWrapper<SysOperLogEntity>()
                    .eq(query.getModule() != null && !query.getModule().isBlank(), SysOperLogEntity::getModule, query.getModule())
                    .like(query.getUsername() != null && !query.getUsername().isBlank(), SysOperLogEntity::getUsername, query.getUsername())
                    .orderByDesc(SysOperLogEntity::getId);
            Page<SysOperLogEntity> page = mapper.selectPage(new Page<>(current, size), wrapper);
            return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                    page.getRecords().stream().map(OperLogRepositoryImpl::toDomain).toList());
        }

        private static OperLog toDomain(SysOperLogEntity entity) {
            OperLog log = new OperLog();
            log.setId(entity.getId());
            log.setUserId(entity.getUserId());
            log.setUsername(entity.getUsername());
            log.setDeptId(entity.getDeptId());
            log.setModule(entity.getModule());
            log.setAction(entity.getAction());
            log.setTargetType(entity.getTargetType());
            log.setTargetId(entity.getTargetId());
            log.setRequestMethod(entity.getRequestMethod());
            log.setRequestUrl(entity.getRequestUrl());
            log.setIp(entity.getIp());
            log.setSuccess(entity.getSuccess());
            log.setErrorMsg(entity.getErrorMsg());
            log.setDetailJson(entity.getDetailJson());
            // 域模型仍用 createTime；库列已是 create_time
            log.setCreateTime(entity.getCreateTime());
            return log;
        }
    }

    /**
     * 登录日志写入。失败登录也要留下，所以不跟外层事务绑在一起。
     */
    @Service
    public static class LoginLogWriterImpl implements LoginLogWriter {

        private final LoginLogRepository repository;
        private final OperatorContext operatorContext;

        /**
         * @param repository      登录日志仓储，内部再开事务
         * @param operatorContext 取 IP 和 User-Agent
         */
        public LoginLogWriterImpl(LoginLogRepository repository, OperatorContext operatorContext) {
            this.repository = repository;
            this.operatorContext = operatorContext;
        }

        /** {@inheritDoc} */
        @Override
        public void success(Long userId, String username, String message) {
            write(userId, username, 1, message);
        }

        /** {@inheritDoc} */
        @Override
        public void failure(String username, String message) {
            write(null, username, 0, message);
        }

        private void write(Long userId, String username, int success, String message) {
            LoginLog log = new LoginLog();
            log.setUserId(userId);
            log.setUsername(username);
            log.setLoginType(LoginType.PASSWORD.getCode());
            log.setIp(operatorContext.clientIp());
            log.setUserAgent(operatorContext.userAgent());
            log.setSuccess(success);
            log.setMessage(message);
            repository.insert(log);
        }
    }

    /**
     * 操作日志写入。摘要序列化成 JSON，避免把普通句子直接塞进 JSON 列。
     */
    @Service
    public static class OperLogWriterImpl implements OperLogWriter {

        private final OperLogRepository repository;
        private final OperatorContext operatorContext;
        private final UserRepository users;
        private final ObjectMapper objectMapper;

        /**
         * @param repository      操作日志仓储
         * @param operatorContext 当前操作者与请求
         * @param users           补部门 id，日志表有 dept_id
         * @param objectMapper    生成合法 JSON
         */
        public OperLogWriterImpl(OperLogRepository repository, OperatorContext operatorContext,
                                 UserRepository users, ObjectMapper objectMapper) {
            this.repository = repository;
            this.operatorContext = operatorContext;
            this.users = users;
            this.objectMapper = objectMapper;
        }

        /** {@inheritDoc} */
        @Override
        @Transactional
        public void write(String module, String action, String targetType, String targetId, String summary) {
            Long userId = operatorContext.userIdOrNull();
            OperLog log = new OperLog();
            log.setUserId(userId);
            log.setUsername(operatorContext.usernameOrNull());
            if (userId != null) {
                users.findById(userId).ifPresent(user -> log.setDeptId(user.getDeptId()));
            }
            log.setModule(module);
            log.setAction(action);
            log.setTargetType(targetType);
            log.setTargetId(targetId);
            log.setRequestMethod(operatorContext.requestMethod());
            log.setRequestUrl(operatorContext.requestUri());
            log.setIp(operatorContext.clientIp());
            log.setSuccess(1);
            log.setDetailJson(toJson(summary));
            repository.insert(log);
        }

        /**
         * JSON 列不接受普通文本。只放 summary，不放请求体。
         */
        private String toJson(String summary) {
            if (summary == null || summary.isBlank()) {
                return null;
            }
            try {
                return objectMapper.writeValueAsString(Map.of("summary", summary));
            } catch (JsonProcessingException ex) {
                return "{\"summary\":\"unserializable\"}";
            }
        }
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
