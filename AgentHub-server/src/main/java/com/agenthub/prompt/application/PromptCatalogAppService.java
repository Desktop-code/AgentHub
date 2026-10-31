package com.agenthub.prompt.application;

import com.agenthub.prompt.api.dto.PromptCatalogConstraintResp;
import com.agenthub.prompt.api.dto.PromptCatalogResp;
import com.agenthub.prompt.api.dto.PromptCatalogTemplateResp;
import com.agenthub.prompt.domain.enums.PromptPublishStatus;
import com.agenthub.prompt.domain.enums.PromptPurpose;
import com.agenthub.prompt.domain.model.PromptConstraint;
import com.agenthub.prompt.domain.model.PromptTemplate;
import com.agenthub.prompt.domain.repository.PromptConstraintRepository;
import com.agenthub.prompt.domain.repository.PromptTemplateRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提示词管理端左栏目录用例。所属域：prompt。
 * <p>
 * 职责：单 locale 下一次查询给出 L1 版本摘要 + L2 **按用途固定的槽位**摘要。
 * 约束：
 * 1. 只读聚合，不写库、不改生效状态；
 * 2. 不返回段落正文，也不做任何拼装 / 变量替换（不碰 PromptAssembler）；
 * 3. L2 左栏是用途槽位而非自由模板列表：一律返回 {@link PromptPurpose} 全部用途各一条，
 *    同用途存多个 template_key 时只绑定其中一个（生效优先），不铺第二张同用途卡。
 */
@Service
public class PromptCatalogAppService {

    private final PromptConstraintRepository constraints;
    private final PromptTemplateRepository templates;

    /**
     * @param constraints L1 仓储
     * @param templates   L2 主表仓储
     */
    public PromptCatalogAppService(PromptConstraintRepository constraints, PromptTemplateRepository templates) {
        this.constraints = constraints;
        this.templates = templates;
    }

    /**
     * 组装左栏目录。
     *
     * @param locale 语言；空白回落 zh-CN
     * @return L1 版本摘要（倒序）+ 按用途的 L2 槽位摘要（枚举顺序）
     */
    public PromptCatalogResp catalog(String locale) {
        String normalized = PromptChecks.locale(locale);
        PromptCatalogResp resp = new PromptCatalogResp();
        resp.setLocale(normalized);
        resp.setConstraints(constraints.listByLocale(normalized).stream().map(this::toConstraintResp).toList());
        resp.setTemplates(purposeSlots(templates.listByLocale(normalized)));
        return resp;
    }

    /**
     * L1 版本行转摘要。只保留卡片需要的字段。
     *
     * @param domain L1 版本
     * @return 摘要
     */
    private PromptCatalogConstraintResp toConstraintResp(PromptConstraint domain) {
        PromptCatalogConstraintResp item = new PromptCatalogConstraintResp();
        item.setId(domain.getId());
        item.setLocale(domain.getLocale());
        item.setVersion(domain.getVersion());
        item.setStatus(domain.getStatus());
        item.setIsActive(domain.getIsActive());
        item.setUpdateTime(domain.getUpdateTime());
        return item;
    }

    /**
     * 把版本行折叠成固定用途槽位。
     * <p>
     * 顺序严格按 {@link PromptPurpose#allCanonical()}，每个用途恒返回一条：
     * 有数据时绑定该用途唯一的 key（生效行所属 key 优先，否则版本最高的 key），
     * 无数据时返回空槽（versionCount=0、templateKey 为约定键），供前端直接展示空态。
     * 用途不在枚举内的历史行不surface：左栏是固定槽位，UI 已无法再造这类用途。
     *
     * @param rows 该 locale 下全部版本行
     * @return 与枚举同序的槽位列表
     */
    private List<PromptCatalogTemplateResp> purposeSlots(List<PromptTemplate> rows) {
        // 先按用途分桶，再在桶内按 key 聚合，便于挑出该用途唯一要展示的 key
        Map<String, List<PromptTemplate>> byPurpose = new LinkedHashMap<>();
        for (PromptTemplate row : rows) {
            if (row.getPurpose() == null || row.getPurpose().isBlank()) {
                continue;
            }
            byPurpose.computeIfAbsent(row.getPurpose(), key -> new ArrayList<>()).add(row);
        }

        List<PromptCatalogTemplateResp> slots = new ArrayList<>();
        for (PromptPurpose purpose : PromptPurpose.allCanonical()) {
            slots.add(buildSlot(purpose.name(), byPurpose.get(purpose.name())));
        }
        return slots;
    }

    /**
     * 构造单个用途槽位摘要。
     *
     * @param purpose 用途码
     * @param rows    该用途下的版本行；null 或空表示空槽
     * @return 槽位摘要
     */
    private PromptCatalogTemplateResp buildSlot(String purpose, List<PromptTemplate> rows) {
        PromptCatalogTemplateResp slot = new PromptCatalogTemplateResp();
        slot.setPurpose(purpose);
        // 空槽也给出约定键（用途名本身），前端新建草稿可直接沿用，不必再猜命名
        slot.setTemplateKey(purpose);
        slot.setName("");
        slot.setVersionCount(0);
        if (rows == null || rows.isEmpty()) {
            return slot;
        }

        // 同用途多 key 时只认一个：生效行所属 key 优先，否则版本最高的 key
        Map<String, List<PromptTemplate>> byKey = new LinkedHashMap<>();
        for (PromptTemplate row : rows) {
            if (row.getTemplateKey() == null || row.getTemplateKey().isBlank()) {
                continue;
            }
            byKey.computeIfAbsent(row.getTemplateKey(), key -> new ArrayList<>()).add(row);
        }
        if (byKey.isEmpty()) {
            return slot;
        }
        String boundKey = pickBoundKey(byKey);
        List<PromptTemplate> boundRows = byKey.get(boundKey);

        slot.setTemplateKey(boundKey);
        slot.setVersionCount(boundRows.size());
        for (PromptTemplate row : boundRows) {
            // 首行取展示信息与最大版本；仓储按 version 倒序，但这里不依赖顺序，显式比较更稳
            if (slot.getLatestId() == null || isNewer(row, slot.getLatestVersion())) {
                slot.setLatestId(row.getId());
                slot.setLatestVersion(row.getVersion());
                slot.setLatestStatus(row.getStatus());
                slot.setName(row.getName());
                slot.setLocale(row.getLocale());
            }
            // 生效行最多一条；已有则保留版本更高的那条，脏数据也不会把标识打乱
            if (isActive(row) && (slot.getActiveId() == null || isNewer(row, slot.getActiveVersion()))) {
                slot.setActiveId(row.getId());
                slot.setActiveVersion(row.getVersion());
            }
            slot.setUpdateTime(latest(slot.getUpdateTime(), row.getUpdateTime()));
        }
        return slot;
    }

    /**
     * 在同一用途的多个 key 中挑出要展示的那个。
     *
     * @param byKey key → 该 key 的版本行
     * @return 选中 key
     */
    private String pickBoundKey(Map<String, List<PromptTemplate>> byKey) {
        String activeKey = null;
        int activeVersion = -1;
        String highestKey = null;
        int highestVersion = -1;
        for (Map.Entry<String, List<PromptTemplate>> entry : byKey.entrySet()) {
            for (PromptTemplate row : entry.getValue()) {
                if (row.getVersion() == null) {
                    continue;
                }
                // 生效优先：同用途至多一条生效，出现多条时取版本更高的
                if (isActive(row) && row.getVersion() > activeVersion) {
                    activeKey = entry.getKey();
                    activeVersion = row.getVersion();
                }
                if (row.getVersion() > highestVersion) {
                    highestKey = entry.getKey();
                    highestVersion = row.getVersion();
                }
            }
        }
        return activeKey != null ? activeKey : highestKey;
    }

    /**
     * @param row     待比较行
     * @param version 当前已记录的版本号；null 视为任意行都更新
     * @return true 表示 row 版本更高
     */
    private boolean isNewer(PromptTemplate row, Integer version) {
        if (row.getVersion() == null) {
            return false;
        }
        return version == null || row.getVersion() > version;
    }

    /**
     * @param row 版本行
     * @return true 表示当前生效（is_active=1 且已发布）
     */
    private boolean isActive(PromptTemplate row) {
        return row.getIsActive() != null && row.getIsActive() == 1
                && row.getStatus() != null && row.getStatus() == PromptPublishStatus.PUBLISHED.getCode();
    }

    /**
     * 取较晚时间；任一为空则返回另一个。
     *
     * @param left  已有时间
     * @param right 新时间
     * @return 较晚时间
     */
    private LocalDateTime latest(LocalDateTime left, LocalDateTime right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return right.isAfter(left) ? right : left;
    }
}
