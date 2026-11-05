package com.agenthub.agent.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.agent.api.dto.MaDefDetailResp;
import com.agenthub.agent.api.dto.MaDefEnabledReq;
import com.agenthub.agent.api.dto.MaDefPageReq;
import com.agenthub.agent.api.dto.MaDefRowResp;
import com.agenthub.agent.api.dto.MaDefSaveReq;
import com.agenthub.agent.application.MaDefAppService;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.api.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 多智能体方案接口。所属域：agent。
 *
 * <p>路径沿用 {@code /api/agent/multi-agents}：本刀把原来的「ROUTER 只读列表」演进为
 * ma_def 方案列表，前端与菜单不需要换入口。
 * <p>控制器零业务：租户、校验、候选纪律、绑根镜像都在 {@link MaDefAppService}。
 * <p>试跑仍在 {@code /api/runtime/trial/**}（MA-P2 刀2 新增 {@code POST /sessions/ma} 走虚拟根），
 * 本控制器只负责方案 CRUD，不承载执行语义。
 */
@RestController
@RequestMapping("/api/agent/multi-agents")
public class MaDefController {

    private final MaDefAppService service;

    /**
     * @param service 多智能体方案用例
     */
    public MaDefController(MaDefAppService service) {
        this.service = service;
    }

    /**
     * 分页查询方案。keyword 搜名称/编码；可筛启停、模板、是否绑根。
     *
     * @param req 分页与筛选
     * @return 一页方案
     */
    @SaCheckPermission(MaPermissions.VIEW)
    @GetMapping
    public Result<PageResult<MaDefRowResp>> page(@Valid MaDefPageReq req) {
        return Result.ok(service.page(req));
    }

    /**
     * 方案详情，含候选与子 Agent 展示信息。
     *
     * @param id 主键
     * @return 详情
     */
    @SaCheckPermission(MaPermissions.VIEW)
    @GetMapping("/{id:\\d+}")
    public Result<MaDefDetailResp> get(@PathVariable Long id) {
        return Result.ok(service.get(id));
    }

    /**
     * 查某个 ROUTER 当前绑定的启用方案；没有则 data 为 null。
     *
     * <p>供旧「编排」Tab 提示「该根已由方案页接管」以及列表深链反查。
     *
     * @param agentId ROUTER 主键
     * @return 绑定方案或 null
     */
    @SaCheckPermission(MaPermissions.VIEW)
    @GetMapping("/by-root/{agentId:\\d+}")
    public Result<MaDefRowResp> getByRoot(@PathVariable Long agentId) {
        return Result.ok(service.getByRoot(agentId));
    }

    /**
     * 新建方案，候选嵌在请求里整份保存。
     *
     * @param req 方案、配置与候选
     * @return 新建后的列表行
     */
    @SaCheckPermission(MaPermissions.EDIT)
    @PostMapping
    public Result<MaDefRowResp> create(@Valid @RequestBody MaDefSaveReq req) {
        return Result.ok(service.create(req));
    }

    /**
     * 更新方案；候选整份替换。
     *
     * @param id  主键
     * @param req 方案、配置与候选
     * @return 更新后的列表行
     */
    @SaCheckPermission(MaPermissions.EDIT)
    @PutMapping("/{id:\\d+}")
    public Result<MaDefRowResp> update(@PathVariable Long id,
                                       @Valid @RequestBody MaDefSaveReq req) {
        return Result.ok(service.update(id, req));
    }

    /**
     * 启停方案。启用时会校验同绑根唯一并镜像到 ROUTER。
     *
     * @param id  主键
     * @param req 目标启停
     * @return 空数据
     */
    @SaCheckPermission(MaPermissions.EDIT)
    @PutMapping("/{id:\\d+}/enabled")
    public Result<Void> setEnabled(@PathVariable Long id, @Valid @RequestBody MaDefEnabledReq req) {
        service.setEnabled(id, req.getEnabled());
        return Result.ok();
    }

    /**
     * 逻辑删除方案；绑定的 ROUTER 保持现状。
     *
     * @param id 主键
     * @return 空数据
     */
    @SaCheckPermission(MaPermissions.EDIT)
    @DeleteMapping("/{id:\\d+}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}
