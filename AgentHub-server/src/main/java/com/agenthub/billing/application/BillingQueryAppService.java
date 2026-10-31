package com.agenthub.billing.application;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.billing.api.dto.DailyPageReq;
import com.agenthub.billing.api.dto.DailyResp;
import com.agenthub.billing.api.dto.DailyTrendPointResp;
import com.agenthub.billing.api.dto.DailyTrendReq;
import com.agenthub.billing.api.dto.LedgerPageReq;
import com.agenthub.billing.api.dto.LedgerResp;
import com.agenthub.billing.domain.model.DailyQuery;
import com.agenthub.billing.domain.model.LedgerQuery;
import com.agenthub.billing.domain.model.TokenDaily;
import com.agenthub.billing.domain.model.TokenLedger;
import com.agenthub.billing.domain.repository.TokenDailyRepository;
import com.agenthub.billing.domain.repository.TokenLedgerRepository;
import com.agenthub.billing.domain.service.TokenTrendSeries;
import com.agenthub.common.api.PageResult;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.model.ModelProvider;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.model.domain.repository.ModelProviderRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 流水和日汇总查询。所属域：billing。只读，不作废。
 * 列表附带 Agent / 模型可读名称；含删查名保留真名，并标记 modelDeleted / modelDisabled（删优先于停）。
 */
@Service
public class BillingQueryAppService {

    private final TokenLedgerRepository ledgers;
    private final TokenDailyRepository daily;
    private final AgentDefRepository agents;
    private final ModelInstanceRepository instances;
    private final ModelProviderRepository providers;

    /**
     * @param ledgers   流水
     * @param daily     日汇总
     * @param agents    解析 Agent 名称
     * @param instances 解析模型实例名称（含已软删）
     * @param providers 解析供应商名称
     */
    public BillingQueryAppService(
            TokenLedgerRepository ledgers,
            TokenDailyRepository daily,
            AgentDefRepository agents,
            ModelInstanceRepository instances,
            ModelProviderRepository providers) {
        this.ledgers = ledgers;
        this.daily = daily;
        this.agents = agents;
        this.instances = instances;
        this.providers = providers;
    }

    /**
     * @param req 分页和筛选
     * @return 流水页（含真名与删/停标记）
     */
    public PageResult<LedgerResp> ledgers(LedgerPageReq req) {
        BillingChecks.dateRange(req.getDateFrom(), req.getDateTo());
        LedgerQuery query = new LedgerQuery();
        query.setCurrent(req.getCurrent());
        query.setSize(req.getSize());
        query.setDateFrom(req.getDateFrom());
        query.setDateTo(req.getDateTo());
        query.setAgentId(req.getAgentId());
        query.setModelInstanceId(req.getModelInstanceId());
        query.setChannel(BillingChecks.channelFilter(req.getChannel()));
        query.setSessionId(req.getSessionId());
        PageResult<TokenLedger> page = ledgers.page(query);
        NameLookup names = loadNames(
                page.getRecords().stream().map(TokenLedger::getAgentId).toList(),
                page.getRecords().stream().map(TokenLedger::getModelInstanceId).toList());
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(row -> toLedger(row, names)).toList());
    }

    /**
     * @param req 分页和筛选
     * @return 日汇总页（含真名与删/停标记）
     */
    public PageResult<DailyResp> daily(DailyPageReq req) {
        BillingChecks.dateRange(req.getDateFrom(), req.getDateTo());
        DailyQuery query = new DailyQuery();
        query.setCurrent(req.getCurrent());
        query.setSize(req.getSize());
        query.setDateFrom(req.getDateFrom());
        query.setDateTo(req.getDateTo());
        query.setAgentId(req.getAgentId());
        query.setModelInstanceId(req.getModelInstanceId());
        query.setChannel(BillingChecks.channelFilter(req.getChannel()));
        PageResult<TokenDaily> page = daily.page(query);
        NameLookup names = loadNames(
                page.getRecords().stream().map(TokenDaily::getAgentId).toList(),
                page.getRecords().stream().map(TokenDaily::getModelInstanceId).toList());
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(row -> toDaily(row, names)).toList());
    }

    /**
     * 趋势图：按业务日把当天所有维度的 token 加总，返回连续日期的点列。
     * 区间内没有汇总的日期补 0 点，保证 X 轴连续、柱子不跳动。
     *
     * @param req 日期区间与可选维度
     * @return 按日期升序的趋势点
     */
    public List<DailyTrendPointResp> trend(DailyTrendReq req) {
        LocalDate today = LocalDate.now();
        // 未指定区间时默认看最近 7 天（含今天），前端首次进页面即有此图
        LocalDate dateTo = req.getDateTo() == null ? today : req.getDateTo();
        LocalDate dateFrom = req.getDateFrom() == null ? dateTo.minusDays(6) : req.getDateFrom();
        BillingChecks.dateRange(dateFrom, dateTo);
        List<TokenDaily> rows = daily.listForTrend(dateFrom, dateTo, req.getAgentId(),
                req.getModelInstanceId(), BillingChecks.channelFilter(req.getChannel()));
        return TokenTrendSeries.build(rows, dateFrom, dateTo).stream().map(point -> {
            DailyTrendPointResp resp = new DailyTrendPointResp();
            resp.setBizDate(point.date());
            resp.setTokenPrompt(point.prompt());
            resp.setTokenCompletion(point.completion());
            resp.setTokenTotal(point.total());
            return resp;
        }).toList();
    }

    /**
     * 本页去重后批量解析名称。
     * 含删查名：有行保留真名；deleted=1 → modelDeleted；未删且停用 → modelDisabled；无行 → 占位。
     * 优先级：删除 &gt; 停用（已删不叠停用标记）。
     */
    private NameLookup loadNames(List<Long> agentIds, List<Long> modelIds) {
        Map<Long, String> agentNames = new HashMap<>();
        Set<Long> seenAgents = new HashSet<>();
        for (Long id : agentIds) {
            if (id == null || !seenAgents.add(id)) {
                continue;
            }
            agents.findById(id).map(AgentDef::getName).filter(n -> n != null && !n.isBlank())
                    .ifPresent(name -> agentNames.put(id, name));
        }

        Map<Long, String> instanceNames = new HashMap<>();
        Map<Long, String> providerNames = new HashMap<>();
        Set<Long> deletedModels = new HashSet<>();
        Set<Long> disabledModels = new HashSet<>();
        Map<Long, String> providerCache = new HashMap<>();
        Set<Long> seenModels = new HashSet<>();
        for (Long id : modelIds) {
            if (id == null || !seenModels.add(id)) {
                continue;
            }
            // 含删一次：账本保留历史 model_instance_id，软删后仍要拼出真名
            ModelInstance instance = instances.findByIdIncludingDeleted(id).orElse(null);
            if (instance == null) {
                instanceNames.put(id, BillingChecks.DELETED_MODEL_NAME);
                deletedModels.add(id);
                continue;
            }
            if (instance.getDisplayName() != null && !instance.getDisplayName().isBlank()) {
                instanceNames.put(id, instance.getDisplayName());
            }
            // 删优先于停：已删只打 deleted，不叠 disabled
            if (isDeleted(instance)) {
                deletedModels.add(id);
            } else if (!RecordStatus.isEnabled(instance.getStatus())) {
                disabledModels.add(id);
            }
            Long providerId = instance.getProviderId();
            if (providerId == null) {
                continue;
            }
            String providerName = providerCache.computeIfAbsent(providerId, pid ->
                    providers.findById(pid).map(ModelProvider::getName).orElse(null));
            if (providerName != null && !providerName.isBlank()) {
                providerNames.put(id, providerName);
            }
        }
        return new NameLookup(agentNames, instanceNames, providerNames, deletedModels, disabledModels);
    }

    /**
     * @param instance 含删查找结果
     * @return true 表示已软删
     */
    private static boolean isDeleted(ModelInstance instance) {
        Integer deleted = instance.getDeleted();
        return deleted != null && deleted != 0;
    }

    private LedgerResp toLedger(TokenLedger ledger, NameLookup names) {
        LedgerResp resp = new LedgerResp();
        resp.setId(ledger.getId());
        resp.setBizDate(ledger.getBizDate());
        resp.setCreateTime(ledger.getCreateTime());
        resp.setAgentId(ledger.getAgentId());
        resp.setAgentName(names.agentName(ledger.getAgentId()));
        resp.setModelInstanceId(ledger.getModelInstanceId());
        resp.setModelInstanceName(names.instanceName(ledger.getModelInstanceId()));
        resp.setProviderName(names.providerName(ledger.getModelInstanceId()));
        resp.setModelDeleted(names.modelDeleted(ledger.getModelInstanceId()));
        resp.setModelDisabled(names.modelDisabled(ledger.getModelInstanceId()));
        resp.setAppId(ledger.getAppId());
        resp.setSessionId(ledger.getSessionId());
        resp.setChannel(ledger.getChannel());
        resp.setTokenPrompt(ledger.getTokenPrompt());
        resp.setTokenCompletion(ledger.getTokenCompletion());
        resp.setTokenTotal(ledger.getTokenTotal());
        resp.setAmount(ledger.getAmount());
        resp.setCurrency(ledger.getCurrency());
        resp.setStatus(ledger.getStatus());
        resp.setRemark(ledger.getRemark());
        return resp;
    }

    private DailyResp toDaily(TokenDaily row, NameLookup names) {
        DailyResp resp = new DailyResp();
        resp.setId(row.getId());
        resp.setBizDate(row.getBizDate());
        resp.setAgentId(row.getAgentId());
        resp.setAgentName(names.agentName(row.getAgentId()));
        resp.setModelInstanceId(row.getModelInstanceId());
        resp.setModelInstanceName(names.instanceName(row.getModelInstanceId()));
        resp.setProviderName(names.providerName(row.getModelInstanceId()));
        resp.setModelDeleted(names.modelDeleted(row.getModelInstanceId()));
        resp.setModelDisabled(names.modelDisabled(row.getModelInstanceId()));
        resp.setAppId(row.getAppId());
        resp.setChannel(row.getChannel());
        resp.setTokenPrompt(row.getTokenPrompt());
        resp.setTokenCompletion(row.getTokenCompletion());
        resp.setTokenTotal(row.getTokenTotal());
        resp.setAmount(row.getAmount());
        resp.setCurrency(row.getCurrency());
        resp.setStatus(row.getStatus());
        return resp;
    }

    /**
     * 本页名称与删/停标记缓存。
     */
    private static final class NameLookup {
        private final Map<Long, String> agentNames;
        private final Map<Long, String> instanceNames;
        private final Map<Long, String> providerNames;
        private final Set<Long> deletedModels;
        private final Set<Long> disabledModels;

        private NameLookup(
                Map<Long, String> agentNames,
                Map<Long, String> instanceNames,
                Map<Long, String> providerNames,
                Set<Long> deletedModels,
                Set<Long> disabledModels) {
            this.agentNames = agentNames;
            this.instanceNames = instanceNames;
            this.providerNames = providerNames;
            this.deletedModels = deletedModels;
            this.disabledModels = disabledModels;
        }

        private String agentName(Long id) {
            return id == null ? null : agentNames.get(id);
        }

        private String instanceName(Long id) {
            return id == null ? null : instanceNames.get(id);
        }

        private String providerName(Long id) {
            return id == null ? null : providerNames.get(id);
        }

        /**
         * @param id 模型实例 id；空 id 不当作已删
         * @return 已软删或不存在时为 true，否则 null（JSON 省略）
         */
        private Boolean modelDeleted(Long id) {
            if (id == null) {
                return null;
            }
            return deletedModels.contains(id) ? Boolean.TRUE : null;
        }

        /**
         * @param id 模型实例 id
         * @return 未删且停用时为 true；已删不返回（删优先于停）
         */
        private Boolean modelDisabled(Long id) {
            if (id == null) {
                return null;
            }
            return disabledModels.contains(id) ? Boolean.TRUE : null;
        }
    }
}
