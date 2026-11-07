package com.agenthub.billing.application;

import com.agenthub.billing.api.dto.DailyRebuildReq;
import com.agenthub.billing.domain.model.DailyTotals;
import com.agenthub.billing.domain.model.TokenDaily;
import com.agenthub.billing.domain.repository.TokenDailyRepository;
import com.agenthub.billing.domain.repository.TokenLedgerRepository;
import com.agenthub.billing.domain.service.DailyRollup;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 按业务日重算日汇总。所属域：billing。
 * 同一维覆盖已有行；区间内不再有正常流水的旧行清零。幂等。
 * 控制台「重算」与定时当日汇总共用 {@link #rebuildRange}，避免两套落库口径。
 */
@Service
public class BillingDailyAppService {

    private final TokenLedgerRepository ledgers;
    private final TokenDailyRepository daily;
    private final OperatorContext operatorContext;

    /**
     * @param ledgers         正常流水
     * @param daily           日汇总
     * @param operatorContext 操作人（仅手动重算需要登录态）
     */
    public BillingDailyAppService(TokenLedgerRepository ledgers, TokenDailyRepository daily,
                                  OperatorContext operatorContext) {
        this.ledgers = ledgers;
        this.daily = daily;
        this.operatorContext = operatorContext;
    }

    /**
     * 控制台手动重算：校验日期后取当前登录人写入审计字段。
     *
     * @param req 闭区间业务日
     * @return 本次有用量的维度数
     */
    @Transactional
    public int rebuild(DailyRebuildReq req) {
        if (req.getDateFrom() == null || req.getDateTo() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择重算日期");
        }
        if (req.getDateFrom().isAfter(req.getDateTo())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "开始日期不能晚于结束日期");
        }
        return rebuildRange(req.getDateFrom(), req.getDateTo(), operatorContext.requireUserId());
    }

    /**
     * 按闭区间从正常流水聚合日汇总并落库。定时任务与手动重算共用。
     * <p>
     * 幂等口径：同维（业务日 + Agent + 模型实例 + 应用 + 渠道）有则覆盖总量，无则插入；
     * 区间内已无正常流水的旧行清零。重复调用结果收敛到同一套数字，不依赖「只跑一次」。
     *
     * @param dateFrom   业务日起（含）
     * @param dateTo     业务日止（含）
     * @param operatorId 写入 created_by / updated_by；定时可用系统占位 id（默认 0）
     * @return 本次有用量的维度数（无流水时为 0，仍可能清零旧行）
     */
    @Transactional
    public int rebuildRange(LocalDate dateFrom, LocalDate dateTo, long operatorId) {
        if (dateFrom == null || dateTo == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择重算日期");
        }
        if (dateFrom.isAfter(dateTo)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "开始日期不能晚于结束日期");
        }
        List<DailyTotals> buckets = DailyRollup.aggregate(ledgers.listNormal(dateFrom, dateTo));
        List<TokenDaily> existing = daily.listRange(dateFrom, dateTo);
        Set<Long> touched = new HashSet<>();
        for (DailyTotals totals : buckets) {
            List<TokenDaily> matches = existing.stream().filter(row -> same(row, totals)).toList();
            if (matches.isEmpty()) {
                daily.insert(totals, operatorId);
                continue;
            }
            for (TokenDaily row : matches) {
                daily.updateTotals(row.getId(), totals, operatorId);
                touched.add(row.getId());
            }
        }
        // 区间里旧维度已经没有正常流水，清零而不是留着上次的数
        for (TokenDaily row : existing) {
            if (!touched.contains(row.getId())) {
                daily.clearTotals(row.getId(), operatorId);
            }
        }
        return buckets.size();
    }

    private boolean same(TokenDaily row, DailyTotals totals) {
        return Objects.equals(row.getBizDate(), totals.getBizDate())
                && Objects.equals(row.getAgentId(), totals.getAgentId())
                && Objects.equals(row.getModelInstanceId(), totals.getModelInstanceId())
                && Objects.equals(row.getAppId(), totals.getAppId())
                && Objects.equals(row.getChannel(), totals.getChannel());
    }
}
