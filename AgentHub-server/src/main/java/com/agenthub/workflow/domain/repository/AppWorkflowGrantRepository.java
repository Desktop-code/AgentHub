package com.agenthub.workflow.domain.repository;

import com.agenthub.workflow.domain.model.AppWorkflowGrant;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 应用-工作流授权仓储。所属域：workflow 的 domain。
 * <p>写入口径：一次保存 = 该 app 的授权集合整体对齐（保留未变化的行与已钉死的 release），
 * 不做逐行 API，避免前端并发保存出现半套数据。
 */
public interface AppWorkflowGrantRepository {

    /**
     * @param appId 应用 id
     * @return 该应用的全部授权（含停用），按主键升序
     */
    List<AppWorkflowGrant> listByAppId(Long appId);

    /**
     * 入站触发用的有效授权。
     *
     * @param appId        应用 id
     * @param workflowDefId 工作流定义 id
     * @return status=1 的授权；无授权或已停用为空
     */
    Optional<AppWorkflowGrant> findEnabled(Long appId, Long workflowDefId);

    /**
     * 用给定 def 集合对齐该应用的授权。
     * <p>新增行 release_id 为空（跟随默认发布指针）；已存在行保留原 release_id，
     * 不在集合内的行物理删除（授权表不含业务历史，可直接删）。
     *
     * @param appId         应用 id
     * @param workflowDefIds 目标工作流定义 id 集合；空表示清空授权
     * @param operatorId    控制台操作人
     */
    void replace(Long appId, Collection<Long> workflowDefIds, Long operatorId);

    /**
     * 应用被删除时清理授权，避免残留行让已删应用继续可用。
     *
     * @param appId 应用 id
     */
    void deleteByAppId(Long appId);
}
