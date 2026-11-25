package com.agenthub.workflow.support;

import com.agenthub.workflow.domain.model.AppWorkflowGrant;
import com.agenthub.workflow.domain.model.WorkflowRelease;
import com.agenthub.workflow.domain.repository.AppWorkflowGrantRepository;
import com.agenthub.workflow.domain.repository.WorkflowReleaseRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 应用授权与发布版本的窄测内存实现。所属域：workflow 的 test support。
 * <p>不注册为 Spring Bean、不连库；只用于验证「授权 + release 解析」这两条业务规则，
 * 行为对齐 MySQL 实现（replace 保留已存在的钉死版本）。
 */
public class WorkflowGrantTestSupport {

    private final Map<Long, AppWorkflowGrant> grants = new LinkedHashMap<>();
    private final Map<Long, WorkflowRelease> releases = new LinkedHashMap<>();
    private final AtomicLong grantSeq = new AtomicLong();
    private final AtomicLong releaseSeq = new AtomicLong();

    /**
     * @return grant 仓储适配器
     */
    public AppWorkflowGrantRepository grants() {
        return new AppWorkflowGrantRepository() {
            @Override
            public List<AppWorkflowGrant> listByAppId(Long appId) {
                return grants.values().stream()
                        .filter(grant -> grant.getAppId().equals(appId))
                        .sorted(Comparator.comparing(AppWorkflowGrant::getId))
                        .toList();
            }

            @Override
            public Optional<AppWorkflowGrant> findEnabled(Long appId, Long workflowDefId) {
                return grants.values().stream()
                        .filter(grant -> grant.getAppId().equals(appId))
                        .filter(grant -> grant.getWorkflowDefId().equals(workflowDefId))
                        .filter(grant -> grant.getStatus() != null && grant.getStatus() == 1)
                        .findFirst();
            }

            @Override
            public void replace(Long appId, Collection<Long> workflowDefIds, Long operatorId) {
                List<Long> targets = workflowDefIds == null ? List.of() : new ArrayList<>(workflowDefIds);
                grants.values().removeIf(grant -> grant.getAppId().equals(appId)
                        && !targets.contains(grant.getWorkflowDefId()));
                for (Long workflowDefId : targets) {
                    boolean exists = grants.values().stream()
                            .anyMatch(grant -> grant.getAppId().equals(appId)
                                    && grant.getWorkflowDefId().equals(workflowDefId));
                    if (exists) {
                        continue;
                    }
                    AppWorkflowGrant grant = new AppWorkflowGrant();
                    grant.setId(grantSeq.incrementAndGet());
                    grant.setAppId(appId);
                    grant.setWorkflowDefId(workflowDefId);
                    grant.setStatus(1);
                    grant.setCreatedBy(operatorId);
                    grant.setCreateTime(LocalDateTime.now());
                    grants.put(grant.getId(), grant);
                }
            }

            @Override
            public void deleteByAppId(Long appId) {
                grants.values().removeIf(grant -> grant.getAppId().equals(appId));
            }
        };
    }

    /**
     * @return release 仓储适配器
     */
    public WorkflowReleaseRepository releases() {
        return new WorkflowReleaseRepository() {
            @Override
            public int maxVersion(Long workflowId) {
                return releases.values().stream()
                        .filter(release -> release.getWorkflowId().equals(workflowId))
                        .map(WorkflowRelease::getVersion)
                        .max(Integer::compareTo)
                        .orElse(0);
            }

            @Override
            public Long insert(WorkflowRelease release) {
                long id = releaseSeq.incrementAndGet();
                release.setId(id);
                releases.put(id, release);
                return id;
            }

            @Override
            public Optional<WorkflowRelease> findById(Long id) {
                return Optional.ofNullable(releases.get(id));
            }

            @Override
            public List<WorkflowRelease> listByWorkflowId(Long workflowId) {
                return releases.values().stream()
                        .filter(release -> release.getWorkflowId().equals(workflowId))
                        .sorted(Comparator.comparing(WorkflowRelease::getVersion).reversed())
                        .toList();
            }
        };
    }

    /**
     * 直接预置一条授权，跳过 save 校验（用于「历史脏授权」场景）。
     *
     * @param appId        应用 id
     * @param workflowDefId 定义 id
     * @param releaseId    钉死版本，可空
     * @return 授权
     */
    public AppWorkflowGrant putGrant(Long appId, Long workflowDefId, Long releaseId) {
        AppWorkflowGrant grant = new AppWorkflowGrant();
        grant.setId(grantSeq.incrementAndGet());
        grant.setAppId(appId);
        grant.setWorkflowDefId(workflowDefId);
        grant.setReleaseId(releaseId);
        grant.setStatus(1);
        grant.setCreateTime(LocalDateTime.now());
        grants.put(grant.getId(), grant);
        return grant;
    }

    /**
     * 预置一个发布版本。
     *
     * @param workflowId 定义 id
     * @param version    版本号
     * @param graphJson  图快照
     * @return 版本
     */
    public WorkflowRelease putRelease(Long workflowId, int version, String graphJson) {
        WorkflowRelease release = new WorkflowRelease();
        release.setId(releaseSeq.incrementAndGet());
        release.setWorkflowId(workflowId);
        release.setVersion(version);
        release.setGraphJson(graphJson);
        release.setFingerprint("fp-" + version);
        release.setPublishTime(LocalDateTime.now());
        releases.put(release.getId(), release);
        return release;
    }
}
