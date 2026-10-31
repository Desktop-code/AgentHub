package com.agenthub.memory.domain.service;

import java.util.function.LongPredicate;

/**
 * 摘要模型解析顺序。所属域：memory。
 * 策略实例 → Agent 绑定 SUMMARY → 平台默认 SUMMARY → YAML 兜底。
 * 都不可用时返回空，调用方必须失败，不能改用主聊天模型。
 */
public final class SummaryModelOrder {

    private SummaryModelOrder() {
    }

    /**
     * @param policyId   策略上的 summary_model_instance_id，可空
     * @param boundId    绑定 purpose=SUMMARY 的实例，可空
     * @param platformId 平台默认 SUMMARY，可空
     * @param yamlId     配置兜底，可空
     * @param usable     实例存在、启用且能走对话
     * @return 第一个可用 id；都没有时为 null
     */
    public static Long first(Long policyId, Long boundId, Long platformId, Long yamlId, LongPredicate usable) {
        Long[] order = {policyId, boundId, platformId, yamlId};
        for (Long id : order) {
            if (id != null && id > 0 && usable.test(id)) {
                return id;
            }
        }
        return null;
    }
}
