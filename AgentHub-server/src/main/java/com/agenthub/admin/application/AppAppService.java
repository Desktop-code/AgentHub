package com.agenthub.admin.application;

import com.agenthub.admin.api.dto.AppAgentReq;
import com.agenthub.admin.api.dto.AppPageReq;
import com.agenthub.admin.api.dto.AppResp;
import com.agenthub.admin.api.dto.AppSaveReq;
import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.admin.domain.model.App;
import com.agenthub.admin.domain.model.AppQuery;
import com.agenthub.admin.domain.model.DataScopeSpec;
import com.agenthub.admin.domain.model.Dept;
import com.agenthub.admin.domain.repository.AppRepository;
import com.agenthub.admin.domain.repository.DeptRepository;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.security.PasswordHasher;
import com.agenthub.common.web.OperatorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * 调用方应用。所属域：admin。
 * 明文密钥只出现在创建和重置的响应里，库中只留哈希。agent id 原样保存。
 * 这是入站调用方密钥。模型出站 Key 在 model 域可解密，不要把模型 Key 改成哈希。
 */
@Service
public class AppAppService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppRepository apps;
    private final DeptRepository depts;
    private final ScopeQueryService scopes;
    private final PasswordHasher passwordHasher;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;

    /**
     * @param apps            应用仓储
     * @param depts           校验归属部门
     * @param scopes          数据范围
     * @param passwordHasher  密钥哈希
     * @param operatorContext 创建人
     * @param operLogWriter   操作日志
     */
    public AppAppService(AppRepository apps, DeptRepository depts, ScopeQueryService scopes, PasswordHasher passwordHasher,
                         OperatorContext operatorContext, OperLogWriter operLogWriter) {
        this.apps = apps;
        this.depts = depts;
        this.scopes = scopes;
        this.passwordHasher = passwordHasher;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
    }

    /**
     * @param req 分页条件
     * @return 范围内的应用，不含密钥
     */
    public PageResult<AppResp> page(AppPageReq req) {
        AppQuery query = new AppQuery();
        query.setCurrent(req.getCurrent());
        query.setSize(req.getSize());
        query.setName(LikeTexts.contains(req.getName()));
        query.setStatus(req.getStatus());
        PageResult<App> page = apps.page(query, scopes.current());
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords().stream().map(this::toResp).toList());
    }

    /**
     * @param id 应用 id
     * @return 应用资料和已授权 agent id
     */
    public AppResp get(Long id) {
        DataScopeSpec scope = scopes.current();
        App app = apps.findById(id).orElse(null);
        scopes.assertAppVisible(app, scope);
        return toResp(app);
    }

    /**
     * @param req 名称和部门
     * @return 含一次性明文密钥
     */
    @Transactional
    public AppResp create(AppSaveReq req) {
        assertDept(req.getDeptId());
        String secret = newSecret();
        App app = new App();
        app.setAppKey(newAppKey());
        app.setName(req.getName().trim());
        app.setDeptId(req.getDeptId());
        app.setStatus(status(req.getStatus()));
        app.setRemark(req.getRemark());
        Long id = apps.insert(app, passwordHasher.hash(secret), operatorContext.requireUserId());
        operLogWriter.write("app", "create", "app", String.valueOf(id), "创建应用 " + app.getName());
        AppResp resp = get(id);
        resp.setAppSecret(secret);
        return resp;
    }

    /**
     * @param id  应用 id
     * @param req 新资料
     * @return 更新后的应用，不含密钥
     */
    @Transactional
    public AppResp update(Long id, AppSaveReq req) {
        App existing = visible(id);
        assertDept(req.getDeptId());
        int newStatus = status(req.getStatus());
        String action = existing.getStatus() != null && existing.getStatus() != newStatus
                ? (RecordStatus.isEnabled(newStatus) ? "enable" : "disable")
                : "update";
        existing.setName(req.getName().trim());
        existing.setDeptId(req.getDeptId());
        existing.setStatus(newStatus);
        existing.setRemark(req.getRemark());
        apps.updateProfile(existing, operatorContext.requireUserId());
        operLogWriter.write("app", action, "app", String.valueOf(id), "更新应用 " + existing.getName());
        return get(id);
    }

    /**
     * @param id 应用 id
     */
    @Transactional
    public void delete(Long id) {
        App existing = visible(id);
        apps.logicDelete(id, operatorContext.requireUserId());
        operLogWriter.write("app", "delete", "app", String.valueOf(id), "删除应用 " + existing.getName());
    }

    /**
     * 重置密钥。旧密钥立即失效，明文只返回这一次。
     *
     * @param id 应用 id
     * @return 只保证 appSecret 有值
     */
    @Transactional
    public AppResp resetSecret(Long id) {
        App existing = visible(id);
        String secret = newSecret();
        apps.updateSecret(id, passwordHasher.hash(secret), operatorContext.requireUserId());
        operLogWriter.write("app", "resetPwd", "app", String.valueOf(id), "重置应用密钥");
        AppResp resp = toResp(existing);
        resp.setAppSecret(secret);
        return resp;
    }

    /**
     * @param id  应用 id
     * @param req agent 主键列表，可为空列表
     */
    @Transactional
    public void grantAgents(Long id, AppAgentReq req) {
        App existing = visible(id);
        apps.replaceAgents(id, req.getAgentIds());
        int count = req.getAgentIds() == null ? 0 : req.getAgentIds().size();
        operLogWriter.write("app", "grant", "app", String.valueOf(id), "授权 Agent 数量 " + count + " / " + existing.getName());
    }

    private App visible(Long id) {
        App app = apps.findById(id).orElse(null);
        scopes.assertAppVisible(app, scopes.current());
        return app;
    }

    private void assertDept(Long deptId) {
        if (deptId == null) {
            return;
        }
        Dept dept = depts.findById(deptId).orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "部门不存在"));
        if (!scopes.deptVisible(dept, scopes.current())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "无权使用该部门");
        }
    }

    private String newAppKey() {
        for (int i = 0; i < 5; i++) {
            byte[] buf = new byte[8];
            RANDOM.nextBytes(buf);
            String key = "app_" + hex(buf);
            if (!apps.appKeyTaken(key)) {
                return key;
            }
        }
        throw new BizException(ErrorCode.DUPLICATE, "生成应用标识失败，请重试");
    }

    private String newSecret() {
        byte[] buf = new byte[24];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }

    private int status(Integer value) {
        return RecordStatus.isEnabled(value == null ? 1 : value) ? 1 : 0;
    }

    private AppResp toResp(App app) {
        AppResp resp = new AppResp();
        resp.setId(app.getId());
        resp.setAppKey(app.getAppKey());
        resp.setName(app.getName());
        resp.setDeptId(app.getDeptId());
        resp.setDeptName(app.getDeptName());
        resp.setStatus(app.getStatus());
        resp.setRemark(app.getRemark());
        resp.setCreateTime(app.getCreateTime());
        resp.setAgentIds(app.getAgentIds());
        return resp;
    }
}
