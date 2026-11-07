package com.agenthub.memory.application;

import com.agenthub.memory.domain.model.ContextTurn;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.service.ExplicitIntentGate;
import com.agenthub.memory.domain.service.LongExtractTriggers;
import com.agenthub.memory.domain.service.MemoryCorrectionSignal;
import com.agenthub.memory.domain.service.MemoryTexts;
import com.agenthub.memory.domain.service.SessionTurnSource;
import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.obs.domain.ObsTraceScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.regex.Pattern;

/**
 * 显式「记住」的异步漏斗：L0 快筛 →（可选）L1 分类 → L2 抽取。所属域：memory。
 *
 * <p>线程契约（N6 红线）：主请求线程只做 L0 正则与「值不值得问 L1」的廉价判断，
 * 一旦需要 L1 或已确定走 L2，就投递到 {@code memoryTaskExecutor} 后立即返回。
 * <b>主线程绝不调用任何模型</b>，否则首字延迟会被最长 60s 的抽取整段吃掉。
 *
 * <p>漏斗：
 * <ol>
 *   <li>L0 命中（记住/请记住/帮我记住/以后默认/以后都按/记一下/帮我记着/别忘了/存一下）
 *       → 跳过 L1，异步直接 L2；</li>
 *   <li>L0 未命中 → 只有明显不像存储意图的（纯问句、过短、招呼致谢）直接结束，
 *       其余投递异步 L1；L1 只回 store/normal，store 才进 L2；</li>
 *   <li>L2 复用现有 MEMORY 抽取与 {@code fact_key/content_hash} 去重，写库在
 *       {@link ExplicitMemoryStore} 的 REQUIRES_NEW 事务里。</li>
 * </ol>
 *
 * <p>结果可见性：本轮不等待新结果，本轮 {@code recallDurable} 读到的是本轮开始前的 LONG，
 * 本次新写的偏好<b>下一轮</b>才注入。禁止为「当轮立刻可见」再做同步等待。
 *
 * <p>失败策略：L1/L2 任何失败只打日志，不抛回主流程，也不影响已经发出的回答。
 */
@Component
public class ExplicitMemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(ExplicitMemoryExtractor.class);

    /** 短指令补上下文的长度阈值：L0 命中的「记住它」等需要上一轮语义才能抽 */
    private static final int SHORT_COMMAND_CHARS = 12;

    /** 补上下文时最多带几轮（user + assistant） */
    private static final int CONTEXT_MAX_TURNS = 4;

    /** 补上下文的字符上限，避免把整段历史塞进抽取出站 */
    private static final int CONTEXT_MAX_CHARS = 2000;

    /** 即使超过长度阈值，这些短语仍视为「省略了宾语」的短指令 */
    private static final Pattern SHORT_COMMAND = Pattern.compile(
            "记住它|记住这个|记住刚才|记一下这个|按这个来|就这个|别忘了|存一下");

    private final ThreadPoolTaskExecutor memoryTaskExecutor;
    private final LongExtractService extracts;
    private final MemoryPolicyAppService policies;
    private final RememberIntentDetector rememberIntent;
    private final MemoryIntentClassifier classifier;
    private final SessionTurnSource events;

    /**
     * @param memoryTaskExecutor 显式记住专用池（有界队列 + Abort），见 {@code AsyncConfig}
     * @param extracts           L2 实际抽取与落库
     * @param policies           池线程上重读策略，让排队期间被关掉的开关生效
     * @param rememberIntent     L0 正则快筛（可吃 yml 配置）
     * @param classifier         L1 store/normal 分类
     * @param events             短指令补上一轮上下文
     */
    public ExplicitMemoryExtractor(@Qualifier("memoryTaskExecutor") ThreadPoolTaskExecutor memoryTaskExecutor,
                                   LongExtractService extracts,
                                   MemoryPolicyAppService policies,
                                   RememberIntentDetector rememberIntent,
                                   MemoryIntentClassifier classifier,
                                   SessionTurnSource events) {
        this.memoryTaskExecutor = memoryTaskExecutor;
        this.extracts = extracts;
        this.policies = policies;
        this.rememberIntent = rememberIntent;
        this.classifier = classifier;
        this.events = events;
    }

    /**
     * 主请求线程调用。只有开关打开、且有可投递的显式意图时才投递，其余情况零额外模型调用。
     * <p>
     * 纠错 / 确认是<b>窄规则</b>识别（{@link MemoryCorrectionSignal}）：命中就跳过 L1 直接进 L2。
     * 原因：这类句子（「不是评估是苹果」「打错了」）通常不是「请记住」，L1 会判 normal 直接丢掉，
     * 用户会觉得「纠正了但记忆没变」。这里只放开这两类信号，<b>没有</b>放宽 L1 的全局门槛。
     *
     * @param policy    主线程已读到的有效策略
     * @param agentId   Agent；空或非正数时不写 LONG（无归属维度）
     * @param userId    平台用户
     * @param sessionId 会话，仅用于日志与计量
     * @param text      当轮用户原文
     * @return 是否已投递（false = 未命中/开关关/无 Agent；主流程无需关心）
     */
    public boolean dispatch(MemoryPolicy policy, Long agentId, Long userId, Long sessionId, String text) {
        // 开关未开：连正则都不用跑，更不会产生任何额外模型调用
        if (!LongExtractTriggers.explicitEnabled(policy)) {
            return false;
        }
        // 没有 Agent 维度就无法按 agent+user 归并（与批抽的 missing_ids 口径一致），不投递
        if (agentId == null || agentId <= 0) {
            log.info("显式LONG跳过 reason=missing_agent sessionId={}", sessionId);
            return false;
        }
        boolean l0Hit = rememberIntent.wantsRemember(text);
        // 纠错 / 确认：用户侧唯一能覆盖旧值的入口；助手单方面纠正不走这里（见批抽正文清洗）
        MemoryCorrectionSignal.Kind correction = MemoryCorrectionSignal.detectUser(text).orElse(null);
        if (!l0Hit && correction == null && !ExplicitIntentGate.shouldClassify(text)) {
            // 纯问句 / 过短无存储意味 / 招呼致谢：直接结束，禁止每句话都打 L1
            log.info("显式LONG跳过 reason=not_store_intent sessionId={} chars={}",
                    sessionId, text == null ? 0 : text.length());
            return false;
        }
        if (correction != null) {
            // 只记类别，不打用户原文（可能含个人信息）
            log.info("显式LONG纠错信号 kind={} sessionId={} chars={}",
                    correction, sessionId, text == null ? 0 : text.length());
        }
        long uid = userId == null ? 0L : userId;
        Map<String, String> mdc = mdcSnapshot(currentTraceId());
        // 纠错 / 确认已经足够明确，不必再花一次 L1 出站去判「值不值得记」
        boolean l1 = !l0Hit && correction == null;
        try {
            memoryTaskExecutor.execute(() -> run(agentId, uid, sessionId, text, l1, correction, mdc));
        } catch (TaskRejectedException ex) {
            // 池与队列都满：丢弃这一次写入，但绝不退回请求线程执行（退回等于把 60s 阻塞塞回首字路径）
            logPoolState();
            log.warn("显式LONG投递被拒绝 reason=pool_saturated sessionId={} agentId={} stage={}",
                    sessionId, agentId, l1 ? "L1" : "L2");
        }
        return true;
    }

    /**
     * 池线程上的实际漏斗。策略在此重读：任务排队期间开关可能被拨回 0，那就不该再出站。
     *
     * @param agentId   Agent
     * @param userId    平台用户
     * @param sessionId 会话，仅用于日志与计量
     * @param text      当轮用户原文
     * @param needL1    true 表示 L0 未命中、需要先跑 L1；false 表示 L0 命中或纠错信号直接 L2
     * @param correction 纠错 / 确认信号；null 表示普通显式通道
     * @param mdc       主线程捕获的 MDC（含 TraceId）
     */
    private void run(long agentId, long userId, Long sessionId, String text, boolean needL1,
                     MemoryCorrectionSignal.Kind correction, Map<String, String> mdc) {
        withMdc(mdc, () -> {
            try {
                MemoryPolicy fresh = policies.effective(agentId);
                if (!LongExtractTriggers.explicitEnabled(fresh)) {
                    log.info("显式LONG跳过 reason=switch_off_on_worker sessionId={} agentId={}",
                            sessionId, agentId);
                    return;
                }
                // L1 只看当轮原文；它要回答的是「这句话有没有明确要求记住」
                if (needL1 && !classifier.shouldStore(fresh, agentId, userId, sessionId, text)) {
                    log.info("显式LONG结束 reason=l1_normal sessionId={} agentId={}", sessionId, agentId);
                    return;
                }
                // L2 才补上下文：「记住它」这类短指令没有宾语，必须借上一轮 USER/ASSISTANT
                String source = l2Source(sessionId, text);
                extracts.onExplicitConfirmed(fresh, agentId, userId, sessionId, source, correction);
            } catch (RuntimeException ex) {
                // onExplicitConfirmed 自身已 catch 抽取失败；这里兜住策略重读、上下文补全等意外
                log.warn("显式LONG任务异常 sessionId={} agentId={} type={} msg={}",
                        sessionId, agentId, ex.getClass().getSimpleName(), ex.getMessage());
            }
        });
    }

    /**
     * 构造 L2 的待抽取正文。短指令会带本会话上一轮 USER/ASSISTANT；其余原样返回。
     *
     * @param sessionId 会话，可空
     * @param text      用户原文
     * @return 送给 L2 的正文，永不为 null
     */
    private String l2Source(Long sessionId, String text) {
        String body = text == null ? "" : text.trim();
        if (sessionId == null || body.isEmpty() || !needsContext(body)) {
            return body;
        }
        List<ContextTurn> turns = recentTurns(sessionId, body);
        if (turns.isEmpty()) {
            return body;
        }
        String context = MemoryTexts.renderTurns(turns).trim();
        if (context.isEmpty()) {
            return body;
        }
        // 明确分段：让抽取模型知道哪段是历史、哪段是「记住它」当前指令
        return "【最近会话】\n" + context + "\n【本轮用户】\n" + body;
    }

    /**
     * 是否是需要补上下文的短指令。
     *
     * @param body 已 trim 的用户原文
     * @return true 表示省略了宾语
     */
    private static boolean needsContext(String body) {
        return body.length() <= SHORT_COMMAND_CHARS || SHORT_COMMAND.matcher(body).find();
    }

    /**
     * 取本会话最近几轮，去掉当前这句用户原文，按时间正序返回。
     *
     * @param sessionId 会话
     * @param current   当前用户原文
     * @return 最近上下文；读取失败时为空列表（不挡 L2 用原文继续）
     */
    private List<ContextTurn> recentTurns(Long sessionId, String current) {
        List<ContextTurn> all;
        try {
            all = events.replay(sessionId);
        } catch (RuntimeException ex) {
            log.warn("显式LONG补上下文失败 sessionId={} type={} msg={}",
                    sessionId, ex.getClass().getSimpleName(), ex.getMessage());
            return List.of();
        }
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        List<ContextTurn> copy = new ArrayList<>(all);
        // 当前用户轮已经入库：从尾部去掉一条同文 USER，避免把「记住它」自己当上下文
        for (int i = copy.size() - 1; i >= 0; i--) {
            ContextTurn turn = copy.get(i);
            if (turn != null && "user".equalsIgnoreCase(turn.getRole())
                    && current.equals(turn.getText() == null ? "" : turn.getText().trim())) {
                copy.remove(i);
                break;
            }
        }
        // 从最近往回取，最多 4 轮 / 2000 字；先取到再反转，保证保留的是离当前最近的内容
        List<ContextTurn> tail = new ArrayList<>();
        int used = 0;
        for (int i = copy.size() - 1; i >= 0 && tail.size() < CONTEXT_MAX_TURNS; i--) {
            ContextTurn turn = copy.get(i);
            if (turn == null || turn.getText() == null || turn.getText().isBlank()) {
                continue;
            }
            int len = turn.getText().trim().length();
            if (used + len > CONTEXT_MAX_CHARS && !tail.isEmpty()) {
                break;
            }
            tail.add(0, turn);
            used += len;
        }
        return tail;
    }

    /**
     * 当前线程的 TraceId；未开追踪时为 null。
     */
    private static String currentTraceId() {
        ObsTraceScope scope = ObsTraceHolder.current();
        return scope == null ? null : scope.traceId();
    }

    /**
     * 捕获主线程 MDC，并把 TraceId 固定成 {@code traceId} 键带进池线程。
     */
    private static Map<String, String> mdcSnapshot(String traceId) {
        Map<String, String> copy = new HashMap<>();
        Map<String, String> current = MDC.getCopyOfContextMap();
        if (current != null) {
            copy.putAll(current);
        }
        if (traceId != null && !traceId.isBlank()) {
            copy.put("traceId", traceId);
        }
        return copy;
    }

    /**
     * 在池线程上还原 MDC，执行完恢复池线程原来的上下文（避免线程复用串日志）。
     */
    private static void withMdc(Map<String, String> mdc, Runnable task) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            if (mdc == null || mdc.isEmpty()) {
                MDC.clear();
            } else {
                MDC.setContextMap(mdc);
            }
            task.run();
        } finally {
            if (previous == null || previous.isEmpty()) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        }
    }

    /**
     * 拒绝时把池状态打进日志：只看一句「已拒绝」无法判断是线程耗尽还是队列积压。
     */
    private void logPoolState() {
        ThreadPoolExecutor pool = memoryTaskExecutor.getThreadPoolExecutor();
        if (pool == null) {
            return;
        }
        log.warn("显式LONG线程池状态 active={} poolSize={} queue={} completed={}",
                pool.getActiveCount(), pool.getPoolSize(), pool.getQueue().size(), pool.getCompletedTaskCount());
    }
}
