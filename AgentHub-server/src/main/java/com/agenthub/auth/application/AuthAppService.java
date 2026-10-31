package com.agenthub.auth.application;

import cn.dev33.satoken.stp.StpUtil;
import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.admin.domain.model.Menu;
import com.agenthub.admin.domain.model.User;
import com.agenthub.admin.domain.repository.DeptRepository;
import com.agenthub.admin.domain.repository.UserRepository;
import com.agenthub.admin.domain.service.MenuTreeBuilder;
import com.agenthub.audit.domain.LoginLogWriter;
import com.agenthub.auth.api.dto.LoginReq;
import com.agenthub.auth.api.dto.LoginResp;
import com.agenthub.auth.api.dto.MeResp;
import com.agenthub.auth.api.dto.MenuNodeResp;
import com.agenthub.common.config.AgentHubProperties;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.security.PasswordHasher;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.spi.identity.SecurityMode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 登录与当前用户信息。所属域：auth。
 * 约束：只实现 security.mode=local；oauth2 / passthrough 直接拒绝，不假装登录成功。
 */
@Service
public class AuthAppService {

    private final UserRepository users;
    private final DeptRepository depts;
    private final PasswordHasher passwordHasher;
    private final LoginLogWriter loginLogWriter;
    private final AccessQueryService accessQuery;
    private final OperatorContext operatorContext;
    private final AgentHubProperties properties;

    /**
     * @param users           平台用户
     * @param depts           补部门名称
     * @param passwordHasher  BCrypt 比对
     * @param loginLogWriter  成败都记
     * @param accessQuery     角色、权限、菜单
     * @param operatorContext 客户端 IP
     * @param properties      安全模式
     */
    public AuthAppService(UserRepository users, DeptRepository depts, PasswordHasher passwordHasher, LoginLogWriter loginLogWriter,
                          AccessQueryService accessQuery, OperatorContext operatorContext, AgentHubProperties properties) {
        this.users = users;
        this.depts = depts;
        this.passwordHasher = passwordHasher;
        this.loginLogWriter = loginLogWriter;
        this.accessQuery = accessQuery;
        this.operatorContext = operatorContext;
        this.properties = properties;
    }

    /**
     * 校验口令并签发 Token。用户不存在和密码错误使用同一错误码。
     *
     * @param req 用户名和明文密码
     * @return 裸 token 与基本资料
     * @throws BizException 模式不是 local、口令错误或账号停用
     */
    public LoginResp login(LoginReq req) {
        if (!SecurityMode.LOCAL.name().equalsIgnoreCase(properties.getSecurity().getMode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前安全模式不支持本地登录");
        }
        User user = users.findByUsername(req.getUsername().trim()).orElse(null);
        // 先比对哈希再区分停用，避免用不同错误码探出账号是否存在
        if (user == null || !passwordHasher.matches(req.getPassword(), user.getPasswordHash())) {
            loginLogWriter.failure(req.getUsername().trim(), "用户名或密码错误");
            throw new BizException(ErrorCode.BAD_CREDENTIALS);
        }
        if (!RecordStatus.isEnabled(user.getStatus())) {
            loginLogWriter.failure(user.getUsername(), "用户已停用");
            throw new BizException(ErrorCode.USER_DISABLED);
        }
        users.updateLoginAudit(user.getId(), operatorContext.clientIp());
        loginLogWriter.success(user.getId(), user.getUsername(), "登录成功");
        StpUtil.login(user.getId());
        // 会话只放登录名，权限每次现查，避免改角色后还拿着旧权限
        StpUtil.getSession().set("username", user.getUsername());
        LoginResp resp = new LoginResp();
        resp.setToken(StpUtil.getTokenValue());
        resp.setUserId(user.getId());
        resp.setUsername(user.getUsername());
        resp.setNickname(user.getNickname());
        return resp;
    }

    /**
     * 注销当前 Token。未登录时由拦截器拒绝，这里不再判断。
     */
    public void logout() {
        StpUtil.logout();
    }

    /**
     * @return 当前用户、角色编码、权限码和导航树
     * @throws BizException 用户已删除或已停用
     */
    public MeResp me() {
        User user = users.findById(operatorContext.requireUserId())
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED));
        if (!RecordStatus.isEnabled(user.getStatus())) {
            StpUtil.logout();
            throw new BizException(ErrorCode.USER_DISABLED);
        }
        MeResp resp = new MeResp();
        resp.setUserId(user.getId());
        resp.setUsername(user.getUsername());
        resp.setNickname(user.getNickname());
        resp.setDeptId(user.getDeptId());
        if (user.getDeptId() != null) {
            depts.findById(user.getDeptId()).ifPresent(dept -> resp.setDeptName(dept.getName()));
        }
        resp.setPlatformAdmin(user.isPlatformAdmin());
        resp.setRoles(accessQuery.roleCodes(user));
        resp.setPermissions(accessQuery.permissions(user));
        resp.setMenus(toNodes(MenuTreeBuilder.buildNavigable(accessQuery.visibleMenus(user))));
        return resp;
    }

    private List<MenuNodeResp> toNodes(List<Menu> menus) {
        List<MenuNodeResp> nodes = new ArrayList<>();
        for (Menu menu : menus) {
            MenuNodeResp node = new MenuNodeResp();
            node.setId(menu.getId());
            node.setParentId(menu.getParentId());
            // 侧栏靠 type 区分可折叠目录与无折叠分组标题
            node.setType(menu.getType());
            node.setName(menu.getName());
            node.setPath(menu.getPath());
            node.setComponent(menu.getComponent());
            node.setIcon(menu.getIcon());
            node.setSort(menu.getSort());
            node.setVisible(menu.getVisible());
            node.setChildren(toNodes(menu.getChildren()));
            nodes.add(node);
        }
        return nodes;
    }
}
