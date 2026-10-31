package com.agenthub.admin.application;

import com.agenthub.admin.api.dto.DeptResp;
import com.agenthub.admin.api.dto.DeptSaveReq;
import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.admin.domain.model.DataScopeSpec;
import com.agenthub.admin.domain.model.Dept;
import com.agenthub.admin.domain.repository.DeptRepository;
import com.agenthub.admin.domain.service.DeptPath;
import com.agenthub.admin.domain.service.DeptTreeBuilder;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 部门树维护。所属域：admin。
 * path 在拿到主键后回写，格式见 {@link DeptPath}（顶层 0,1，子级 0,1,3）；
 * 移动时按前缀改写子孙，禁止挂到自己的下级。
 */
@Service
public class DeptAppService {

    private final DeptRepository depts;
    private final ScopeQueryService scopes;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;

    /**
     * @param depts           部门仓储
     * @param scopes          数据范围
     * @param operatorContext 操作者
     * @param operLogWriter   操作日志
     */
    public DeptAppService(DeptRepository depts, ScopeQueryService scopes, OperatorContext operatorContext, OperLogWriter operLogWriter) {
        this.depts = depts;
        this.scopes = scopes;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
    }

    /**
     * @return 当前用户能看到的部门森林
     */
    public List<DeptResp> tree() {
        DataScopeSpec scope = scopes.current();
        List<Dept> visible = depts.listAll().stream().filter(dept -> scopes.deptVisible(dept, scope)).toList();
        return toResps(DeptTreeBuilder.build(visible));
    }

    /**
     * 先插入再回写 path，因为前缀里要带自己的主键。
     *
     * @param req 新部门
     * @return 含 path 的部门
     */
    @Transactional
    public DeptResp create(DeptSaveReq req) {
        long parentId = parentId(req.getParentId());
        if (parentId != 0 && !scopes.deptVisible(depts.findById(parentId).orElseThrow(
                () -> new BizException(ErrorCode.BAD_REQUEST, "父部门不存在")), scopes.current())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "无权在该部门下新增");
        }
        String parentPath = parentPath(parentId);
        Dept dept = new Dept();
        dept.setParentId(parentId);
        dept.setName(req.getName().trim());
        dept.setPath("");
        dept.setSort(req.getSort() == null ? 0 : req.getSort());
        dept.setLeaderUserId(req.getLeaderUserId());
        dept.setPhone(req.getPhone());
        dept.setStatus(status(req.getStatus()));
        Long id = depts.insert(dept);
        // path 依赖自身主键，只能插入后回写：顶级 0,id，其余 父path,id
        String path = DeptPath.ofChild(parentPath, id);
        depts.updatePath(id, path);
        operLogWriter.write("dept", "create", "dept", String.valueOf(id), "创建部门 " + dept.getName());
        return toResp(depts.findById(id).orElseThrow());
    }

    /**
     * 父部门变化时才改写子树 path。
     *
     * @param id  部门 id
     * @param req 新资料
     * @return 更新后的部门
     */
    @Transactional
    public DeptResp update(Long id, DeptSaveReq req) {
        Dept dept = required(id);
        long parentId = parentId(req.getParentId());
        dept.setName(req.getName().trim());
        dept.setSort(req.getSort() == null ? 0 : req.getSort());
        dept.setLeaderUserId(req.getLeaderUserId());
        dept.setPhone(req.getPhone());
        dept.setStatus(status(req.getStatus()));
        depts.updateProfile(dept);
        if (parentId != (dept.getParentId() == null ? 0L : dept.getParentId())) {
            move(dept, parentId);
        }
        operLogWriter.write("dept", "update", "dept", String.valueOf(id), "更新部门 " + dept.getName());
        return toResp(depts.findById(id).orElseThrow());
    }

    /**
     * @param id 部门 id
     * @throws BizException 仍有子部门或用户
     */
    @Transactional
    public void delete(Long id) {
        Dept dept = required(id);
        if (depts.hasChildren(id)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先删除下级部门");
        }
        if (depts.hasUsers(id)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "部门下仍有用户");
        }
        depts.logicDelete(id, operatorContext.requireUserId());
        operLogWriter.write("dept", "delete", "dept", String.valueOf(id), "删除部门 " + dept.getName());
    }

    /**
     * 新父 path 若覆盖当前 path，说明新父在自己的子树里。
     */
    private void move(Dept dept, long parentId) {
        if (parentId == dept.getId()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能把部门挂到自己下面");
        }
        String newPrefix;
        if (parentId == 0) {
            newPrefix = DeptPath.ofRoot(dept.getId());
        } else {
            Dept parent = depts.findById(parentId).orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "父部门不存在"));
            // 目标父节点在新树里若已在自己的子树内，移动会形成环
            if (DeptPath.covers(dept.getPath(), parent.getPath())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "不能把部门移动到自己的下级");
            }
            newPrefix = DeptPath.ofChild(parent.getPath(), dept.getId());
        }
        depts.rewritePath(dept.getId(), parentId, dept.getPath(), newPrefix, operatorContext.requireUserId());
    }

    private String parentPath(long parentId) {
        if (parentId == 0) {
            return DeptPath.ROOT;
        }
        Dept parent = depts.findById(parentId).orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "父部门不存在"));
        if (parent.getPath() == null || parent.getPath().isBlank()) {
            return DeptPath.ofRoot(parent.getId());
        }
        return parent.getPath();
    }

    private long parentId(Long parentId) {
        return parentId == null ? 0L : parentId;
    }

    private int status(Integer status) {
        return RecordStatus.isEnabled(status == null ? 1 : status) ? 1 : 0;
    }

    private Dept required(Long id) {
        return depts.findById(id).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "部门不存在"));
    }

    private List<DeptResp> toResps(List<Dept> depts) {
        List<DeptResp> list = new ArrayList<>();
        for (Dept dept : depts) {
            DeptResp resp = toResp(dept);
            resp.setChildren(toResps(dept.getChildren()));
            list.add(resp);
        }
        return list;
    }

    private DeptResp toResp(Dept dept) {
        DeptResp resp = new DeptResp();
        resp.setId(dept.getId());
        resp.setParentId(dept.getParentId());
        resp.setName(dept.getName());
        resp.setPath(dept.getPath());
        resp.setSort(dept.getSort());
        resp.setLeaderUserId(dept.getLeaderUserId());
        resp.setPhone(dept.getPhone());
        resp.setStatus(dept.getStatus());
        return resp;
    }
}
