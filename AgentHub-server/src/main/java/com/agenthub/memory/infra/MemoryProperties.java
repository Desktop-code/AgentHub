package com.agenthub.memory.infra;

import com.agenthub.memory.domain.model.MemoryPolicy;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆与窗口的平台默认和硬顶。所属域：memory。
 * Agent 策略可以更小，不能超过这里的 max。摘要兜底实例只在三级解析都空时使用。
 * 批抽总闸 {@code longExtractBatchEnabled}：代码兜底默认 false（配置缺键即关死 session_end/summary 出站）；
 * 随仓 {@code application.yml} 已显式置 true 作为发布口径，生产止血用环境变量覆盖，不改默认值。
 */
@Data
@Component
@ConfigurationProperties(prefix = "agenthub.memory")
public class MemoryProperties {

    private int shortDefaultTtlSec = 86400;
    private int shortDefaultTtlMaxSec = 604800;
    /**
     * 近轮滑动窗：最多保留多少轮（user + assistant 各算一轮）。
     * <p>
     * 默认从 20 降到 12：每轮都要在每轮对话里重新出站，20 轮往往只是把同一段历史反复计费；
     * 更早的内容已由滚动摘要覆盖（摘要与近轮已去重，不会双份）。
     */
    private int ctxMaxTurns = 12;
    private int ctxMaxTurnsCap = 50;
    /**
     * 近轮滑动窗的字符上限。默认从 32000 降到 16000：
     * 32000 字已接近多数模型的上下文窗口大头，留给系统提示与工具结果的空间会被压到很小。
     */
    private int ctxMaxChars = 16000;
    private int ctxMaxCharsCap = 64000;
    /**
     * 窗口空闲 TTL 秒。同一列也决定「空闲多久后收口并批抽 LONG」的到期时间：
     * 调度读策略（{@code TrialSessionCloser.idleSec}），不写死默认值；下限 60。
     */
    private int ctxIdleTtlSec = 7200;
    private int ctxIdleTtlMaxSec = 86400;
    private int recallMaxChars = 4000;
    /**
     * LONG 注入条数（Top N）。默认 5：全量塞进提示词既贵又会让模型抓不住重点。
     * 取 importance 降序、再按 update_time 新近排序后的前 N 条；库内条目不受影响。
     */
    private int recallLongTopN = 5;
    /** Top N 的硬顶。策略页/环境变量配得再大也压到这里，避免一次注入几十条 */
    private int recallLongTopNCap = 20;
    /**
     * 召回 importance 下限（0~100）。默认 0 = 不过滤。
     * <p>
     * 向量路径与 MySQL 降级路径共用同一门槛：importance 低于该值的条目先丢弃，再截 Top N。
     * 只影响本轮注入，不改库内条目，也不参与抽取/合并。
     */
    private int recallImportanceMin = 0;
    /**
     * 召回近义去重开关。<b>默认 false</b>：行为与未上线该能力时完全一致。
     * <p>
     * 只在本轮注入前做贪心去重，不删库、不自动合并 fact_key。
     */
    private boolean recallNearDupEnabled = false;
    /**
     * 近义去重相似度阈值（0~1）。默认 0.92。
     * <p>
     * 仅向量路径用正文相似度判定；MySQL-only 路径没有向量分，改用正文规范化指纹判等。
     * 越界值由 {@link #recallNearDupSimilarity()} 钳制到合法区间。
     */
    private double recallNearDupSimilarity = 0.92;
    /**
     * 记忆专用 Milvus collection。与 RAG 知识库集合（{@code agenthub_kb_*}）分离：
     * 同一 Milvus 实例上物理隔离，禁止把 LONG 向量写进 KB 集合。
     * 必须落在 {@code agenthub_memory_} 命名空间内，否则 isReady=false。
     */
    private String milvusCollection = "agenthub_memory_long";
    /**
     * 记忆向量维度。已降级为「排障对照值」，<b>不再是建集真源</b>：
     * ensure / 写入一律读平台级 Embedding 实例最近一次连通测试成功快照
     * {@code model_instance.embedding_dim}；快照缺失即 isReady=false。
     * 保留本键只为兼容旧配置文件与运维对照，避免启动因缺键报错。
     */
    private int embeddingDimension = 768;
    /**
     * 平台级 Embedding / Rerank 解析结果的短缓存毫秒数。默认 30000。
     * <p>
     * 设置/取消平台级、换绑、连通测试写回维度快照时由模型中心主动失效；
     * 本 TTL 只兜「别处直接改库」的场景，避免每次调用都查 {@code model_instance}。
     */
    private long platformModelCacheMs = 30_000L;
    /**
     * 向量双写（Embedding + Milvus upsert）失败后的尝试次数上限，含首次。默认 3。
     * 耗尽只打日志、不回滚 MySQL、不注册定时重炸。
     */
    private int vectorWriteMaxAttempts = 3;
    /**
     * 两次向量写尝试之间的退避毫秒。默认 200；0=不等待（仅测试或极端排障时用）。
     */
    private long vectorWriteRetryBackoffMs = 200L;
    /**
     * 召回向量候选条数（Milvus topK）。比注入 N 略大，给「对齐剔除已删/过期」和重排留余量。
     */
    private int recallVectorTopK = 20;
    /**
     * 召回侧 Embedding 单次等待上限毫秒。超时立即降级 MySQL Top N，不让主对话被一次出站拖住。
     */
    private long recallVectorTimeoutMs = 2500L;
    /**
     * 召回侧重排（平台级 Rerank）单次等待上限毫秒。超时退回 importance 序，不影响本轮注入。
     */
    private long recallRerankTimeoutMs = 2500L;
    /**
     * 召回侧单轮尝试次数（含首次）。默认 1：严格「每轮每类出站 ≤1」；
     * 允许配 2 表示最多一次短重试，超过 2 由代码压回 2。
     */
    private int recallVectorMaxAttempts = 1;
    /**
     * 存量补同步单轮最多推进条数。默认 200：一次手动触发只补一小批，
     * 禁止变成启动全量或定时全量；不够就再触发一轮。
     */
    private int vectorReconcileBatchLimit = 200;
    /**
     * 补同步允许的最大失败次数。默认 3：达到后行停在 FAILED、只打日志，不再被捞取。
     */
    private int vectorReconcileMaxFailCount = 3;
    /**
     * 补同步的应用内定时开关。<b>默认 false</b>：存量补同步会真出站（Embedding + Milvus），
     * 默认不自动跑，避免「装好就开始烧配额」；显式打开后按 {@link #vectorReconcileDelayMs} 周期推进。
     * <p>
     * 关掉只是不自动跑，手动 {@code POST /api/memory/vector/reconcile} 依然可用。
     */
    private boolean vectorReconcileScheduleEnabled = false;
    /**
     * 补同步定时轮询间隔（毫秒，fixedDelay）。默认 1800000 = 30 分钟：
     * 比过期清理（1 小时）更密一点，但远慢于主对话写入，不会与实时双写抢配额。
     */
    private long vectorReconcileDelayMs = 1_800_000L;
    /**
     * 「重建未完成」期间自动续补开关。<b>默认 true</b>。
     * <p>
     * 与 {@link #vectorReconcileScheduleEnabled} 刻意分开：重建是操作者显式确认过的一次性动作，
     * 已经接受了「全量重排 + 出站补写」的代价，因此必须自动跑完，不能要求人守在页面上反复点
     * 「继续补同步」。全局定时仍默认关，避免「装好就开始烧配额」。
     * <p>
     * 关掉后退化为旧行为：重建只做有界一轮，剩余靠手动接口推进。
     */
    private boolean vectorRebuildDrainEnabled = true;
    /**
     * 重建自动续补的轮询间隔（毫秒）。默认 3000。
     * <p>
     * 只在该进程持有「重建未完成」标记时生效；没有重建时本任务一次库都不查。
     */
    private long vectorRebuildDrainDelayMs = 3_000L;
    /**
     * 重建自动续补的退避上限（毫秒）。默认 30000。
     * <p>
     * 单轮没有任何进展时（Embedding 故障 / 未就绪）按 2 倍退避到该上限，
     * 避免每 3 秒重复出站把配额和日志打满；一旦有进展立刻回到基础间隔。
     */
    private long vectorRebuildDrainMaxBackoffMs = 30_000L;
    /**
     * 一轮过期清理最多处理的 MySQL 行数。默认 2000。
     * <p>
     * 过期清理原先一次扫全表，行数大时会长时间持锁、并把整批主键塞给向量删除。
     * 加此上限后每轮只推进一批；未清完的下一轮继续（按 id 升序，进度单调，不会饿死）。
     * 过期行本来就被读路径过滤掉，短暂积压只影响表大小，不影响可用性。
     */
    private int expiredPurgeBatchLimit = 2000;
    /**
     * 单次向量删除请求携带的主键上限。默认 500。
     * <p>
     * 超过则拆成多批串行发送（每批一次出站），避免一批几万个 id 直接打爆 Milvus/网关。
     */
    private int vectorDeleteBatchLimit = 500;
    /** 空字符串表示没有 YAML 兜底（SUMMARY） */
    private String summaryFallbackInstanceId = "";
    /** MEMORY 抽取环境兜底；空表示无 */
    private String memoryFallbackInstanceId = "";
    private long cleanupDelayMs = 3_600_000L;
    /**
     * 批抽出站总闸（session_end / summary）。<b>Java 兜底默认 false</b>：配置文件缺键时一键关死出站批抽，
     * 不影响 explicit「记住」（走独立通道）。
     * <p>
     * 发布口径：随仓 {@code application.yml} 已显式置 {@code true}，即默认允许空闲批抽出站；
     * 生产止血请用环境变量 {@code AGENTHUB_MEMORY_LONG_EXTRACT_BATCH_ENABLED=false} 覆盖，
     * 不要把仓库默认值改回 false（会让所有环境永久失去批抽）。
     */
    private boolean longExtractBatchEnabled = false;
    /**
     * 单次 CloseJob 轮次最多收口并批抽的空闲会话数。默认 5，串行处理。
     */
    private int sessionCloseBatchLimit = 5;
    /**
     * 会话级批抽最短正文字符；低于则 skip 且不调模型。
     */
    private int longExtractMinChars = 32;
    /**
     * 两次批抽之间的间隔毫秒（CloseJob 串行节流）。0=不额外 sleep。
     */
    private long longExtractIntervalMs = 200L;
    /**
     * fact_key 同义扩展：别名 → 规范键。与内置表合并，同名覆盖。
     * 例：{@code living_place: residence_city}
     */
    private Map<String, String> factKeyAliases = new LinkedHashMap<>();

    /**
     * L0 显式「记住」正则片段。空列表 = 使用 {@link com.agenthub.memory.domain.service.MemoryTexts}
     * 的内置默认；非空则整体替换默认。
     * <p>
     * 只影响「用户当轮原文」的显式通道快筛，不参与会话结束/摘要批抽，
     * 也不在 Agent 记忆页暴露（避免把代码级规则配置成业务开关）。
     */
    private List<String> rememberPatterns = new ArrayList<>();

    /**
     * 没有策略行时按平台默认。长期记忆与批抽默认关，避免没配置就抽偏好。
     *
     * @param agentId Agent
     * @return 未入库的有效策略
     */
    public MemoryPolicy defaults(Long agentId) {
        MemoryPolicy policy = new MemoryPolicy();
        policy.setAgentId(agentId);
        policy.setEnableShort(1);
        policy.setEnableLong(0);
        policy.setMaxShortItems(20);
        policy.setMaxLongItems(100);
        policy.setCtxMaxTurns(Math.min(ctxMaxTurns, ctxMaxTurnsCap));
        policy.setCtxMaxChars(Math.min(ctxMaxChars, ctxMaxCharsCap));
        policy.setCtxIdleTtlSec(Math.min(ctxIdleTtlSec, ctxIdleTtlMaxSec));
        policy.setShortDefaultTtlSec(Math.min(shortDefaultTtlSec, shortDefaultTtlMaxSec));
        // 批抽默认关；显式记住仍可走 long_extract_on_explicit（不依赖本开关）
        policy.setEnableLongExtract(0);
        policy.setLongExtractOnSessionEnd(1);
        policy.setLongExtractOnExplicit(1);
        policy.setStatus(1);
        return policy;
    }

    /**
     * 运行时读取已保存的策略时压到硬顶，避免库里的旧值超过平台上限。
     *
     * @param policy 库中的策略
     * @return 同一对象
     */
    public MemoryPolicy clamp(MemoryPolicy policy) {
        policy.setCtxMaxTurns(cap(policy.getCtxMaxTurns(), 1, ctxMaxTurnsCap));
        policy.setCtxMaxChars(cap(policy.getCtxMaxChars(), 256, ctxMaxCharsCap));
        policy.setCtxIdleTtlSec(cap(policy.getCtxIdleTtlSec(), 60, ctxIdleTtlMaxSec));
        policy.setShortDefaultTtlSec(cap(policy.getShortDefaultTtlSec(), 60, shortDefaultTtlMaxSec));
        if (policy.getMaxShortItems() == null || policy.getMaxShortItems() < 1) {
            policy.setMaxShortItems(20);
        }
        if (policy.getMaxLongItems() == null || policy.getMaxLongItems() < 1) {
            policy.setMaxLongItems(100);
        }
        return policy;
    }

    /**
     * @return SUMMARY 的 YAML 兜底实例；没配则为 null
     */
    public Long fallbackInstanceId() {
        return parseId(summaryFallbackInstanceId);
    }

    /**
     * @return MEMORY 抽取的 YAML 兜底实例；没配则为 null
     */
    public Long memoryFallbackInstanceId() {
        return parseId(memoryFallbackInstanceId);
    }

    /**
     * LONG 注入条数：按配置取，并压到硬顶。
     *
     * @return 1..{@code recallLongTopNCap} 之间的条数
     */
    public int longRecallTopN() {
        int configured = recallLongTopN < 1 ? 5 : recallLongTopN;
        int cap = recallLongTopNCap < 1 ? 20 : recallLongTopNCap;
        return Math.min(configured, cap);
    }

    /**
     * 召回 importance 下限，越界钳制到 [0,100]。
     *
     * @return 合法下限；0 表示不过滤
     */
    public int recallImportanceMin() {
        return cap(recallImportanceMin, 0, 100);
    }

    /**
     * 近义去重相似度阈值，越界钳制到 [0,1]；非数值回落默认 0.92。
     *
     * @return 合法阈值
     */
    public double recallNearDupSimilarity() {
        if (Double.isNaN(recallNearDupSimilarity)) {
            return 0.92d;
        }
        if (recallNearDupSimilarity < 0d) {
            return 0d;
        }
        if (recallNearDupSimilarity > 1d) {
            return 1d;
        }
        return recallNearDupSimilarity;
    }

    private static Long parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long id = Long.parseLong(raw.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static int cap(Integer raw, int min, int max) {
        int value = raw == null ? min : raw;
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }
}
