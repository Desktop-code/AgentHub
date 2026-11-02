package com.agenthub.runtime.application;

import com.agenthub.common.error.BizException;
import com.agenthub.memory.application.MemoryPolicyAppService;
import com.agenthub.memory.application.SummaryModelResolver;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.service.MemoryModelClient;
import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.TitleSource;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import com.agenthub.runtime.domain.service.PromptInjectionHolder;
import com.agenthub.runtime.domain.service.SessionTitleTexts;
import com.agenthub.runtime.infra.agentscope.TrialProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 首轮对话结束后异步生成会话标题。所属域：runtime。
 *
 * <p>职责：拿一轮的问答正文，让 SUMMARY 槽模型概括成不超过 {@value SessionTitleTexts#TITLE_LIMIT}
 * 个汉字的短标题，写回 {@code agent_run_session.session_title}，来源标为 AUTO。
 *
 * <p>关键约束：
 * <ol>
 *   <li>跑在 {@code lightTaskExecutor} 上：这是「不阻塞用户响应、失败可降级」的轻量任务，
 *       不得丢进 router 池，也不能退回无界线程池；</li>
 *   <li>只在标题为空时写：用户可能已经手改（title_source=MANUAL），那种情况一律放弃。
 *       应用层判空只为省一次出站，真正的并发保护是仓储里那条带条件的 UPDATE；</li>
 *   <li>任何失败只记 warn 日志、不抛给调用方：标题是锦上添花，不能回头污染已经落库的一轮对话；</li>
 *   <li>异步线程没有 Sa-Token 登录态，也不继承 {@code ObsTraceHolder} / {@code PromptInjectionHolder}，
 *       因此本类不读 {@code OperatorContext}，用户 id 一律从会话行取；出站仍走
 *       {@link MemoryModelClient}，计量渠道固定 CONSOLE。</li>
 * </ol>
 */
@Service
public class SessionTitleGeneratorAppService {

    private static final Logger log = LoggerFactory.getLogger(SessionTitleGeneratorAppService.class);

    /** 送进模型的单侧正文上限。标题只取主题，长正文既浪费 token 也容易让模型跑题 */
    private static final int SOURCE_LIMIT = 2000;

    /**
     * 标题提示词。硬性要求三点：只输出标题本身、不超过 10 个汉字、不要标点与解释。
     * 模型不一定会照做，因此后面还有一道 {@link SessionTitleTexts#clean(String)} 兜底。
     */
    private static final String INSTRUCTION = """
            请用不超过 10 个汉字概括以下对话的主题。直接输出标题，不要任何标点符号、引号或解释性文字。
            """;

    private final ThreadPoolTaskExecutor lightTaskExecutor;
    private final SummaryModelResolver summaryResolver;
    private final MemoryPolicyAppService memoryPolicies;
    private final MemoryModelClient models;
    private final RunSessionRepository sessions;
    private final TrialProperties properties;

    /**
     * @param lightTaskExecutor 标题任务实际运行的池。持有引用用于运行期观测（队列积压 / 占用线程数）：
     *                          本池拒绝策略是 CallerRuns，池满时任务会退回提交线程，运维需要能看到
     * @param summaryResolver   SUMMARY 槽解析（策略首选 → 槽 → 平台 → YAML → 可降 CHAT）
     * @param memoryPolicies    取该 Agent 的有效记忆策略，让标题跟随用户配置的摘要模型
     * @param models            出站调用，自带超时
     * @param sessions          读写 agent_run_session
     * @param properties        自动标题开关
     */
    public SessionTitleGeneratorAppService(@Qualifier("lightTaskExecutor") ThreadPoolTaskExecutor lightTaskExecutor,
                                           SummaryModelResolver summaryResolver,
                                           MemoryPolicyAppService memoryPolicies,
                                           MemoryModelClient models,
                                           RunSessionRepository sessions,
                                           TrialProperties properties) {
        this.lightTaskExecutor = lightTaskExecutor;
        this.summaryResolver = summaryResolver;
        this.memoryPolicies = memoryPolicies;
        this.models = models;
        this.sessions = sessions;
        this.properties = properties;
    }

    /**
     * 首轮对话结束后异步生成标题。调用方在整轮落库之后触发，不等待返回、不关心成败。
     *
     * @param sessionId       会话主键
     * @param agentId         Agent 主键，用于解析 SUMMARY 槽并计量
     * @param userQuery       用户这一轮的原始提问
     * @param assistantAnswer 助手这一轮的完整回答
     */
    @Async("lightTaskExecutor")
    public void asyncGenerateTitle(Long sessionId, Long agentId, String userQuery, String assistantAnswer) {
        if (sessionId == null || agentId == null) {
            log.warn("自动生成会话标题跳过 reason=missing_ids sessionId={} agentId={}", sessionId, agentId);
            return;
        }
        if (!properties.isAutoTitleEnabled()) {
            log.info("自动生成会话标题跳过 reason=switch_off sessionId={}", sessionId);
            return;
        }
        // 池线程可能先被别的任务借过：清掉线程级埋点，避免把上一次请求的 trace / 注入统计
        // 带进本任务的 LLM span（本任务没有自己的请求上下文，这些值都应为空）
        ObsTraceHolder.clear();
        PromptInjectionHolder.clear();
        logPoolState(sessionId);
        try {
            // 防御校验：任务排队期间用户可能已手改名，或已被另一条任务写过标题。
            // 有值就直接放弃，省掉一次出站；并发保护在仓储的条件 UPDATE 上。
            Optional<RunSession> found = sessions.findById(sessionId);
            if (found.isEmpty()) {
                log.info("自动生成会话标题跳过 reason=session_gone sessionId={}", sessionId);
                return;
            }
            RunSession session = found.get();
            if (hasTitle(session)) {
                log.info("自动生成会话标题跳过 reason=already_titled sessionId={} titleSource={}",
                        sessionId, session.getTitleSource());
                return;
            }
            String dialogue = dialogue(userQuery, assistantAnswer);
            if (dialogue.isEmpty()) {
                log.info("自动生成会话标题跳过 reason=empty_dialogue sessionId={}", sessionId);
                return;
            }
            // 解析 SUMMARY 槽：与滚动摘要共用同一套三级解析，未配摘要模型时允许降到 CHAT
            long modelId = requireSummaryModel(agentId);
            long userId = session.getUserId() == null ? 0L : session.getUserId();
            String rawTitle = models.complete(agentId, sessionId, userId, modelId, INSTRUCTION, dialogue);
            String cleanTitle = SessionTitleTexts.clean(rawTitle);
            if (cleanTitle.isEmpty()) {
                log.warn("自动生成会话标题放弃 reason=empty_after_clean sessionId={} modelId={} rawChars={}",
                        sessionId, modelId, rawTitle == null ? 0 : rawTitle.length());
                return;
            }
            // 条件写入：会话此刻仍无标题且未被手改才会命中，避免覆盖用户手动改的名字
            boolean written = sessions.updateAutoTitleIfAbsent(sessionId, cleanTitle, userId);
            log.info("自动生成会话标题结束 sessionId={} agentId={} modelId={} title={} written={}",
                    sessionId, agentId, modelId, cleanTitle, written);
        } catch (Exception e) {
            // 标题失败不能污染已经结束的一轮：只记日志，不重试、不抛给异步框架
            log.warn("自动生成会话标题失败, sessionId={}", sessionId, e);
        }
    }

    /**
     * 解析该 Agent 的 SUMMARY 模型实例。
     *
     * @param agentId Agent 主键
     * @return 可用实例 id
     * @throws BizException 三级解析与 CHAT 降级都无可用实例
     */
    private long requireSummaryModel(Long agentId) {
        MemoryPolicy policy = memoryPolicies.effective(agentId);
        return summaryResolver.requireSummary(policy, agentId);
    }

    /**
     * 判断该会话是否已有标题（含用户手改）。
     *
     * @param session 会话
     * @return true 表示已有标题或有手改标记，本次应放弃
     */
    private static boolean hasTitle(RunSession session) {
        if (TitleSource.MANUAL.name().equals(session.getTitleSource())) {
            return true;
        }
        return session.getSessionTitle() != null && !session.getSessionTitle().isBlank();
    }

    /**
     * 拼本轮问答正文，任何一侧为空都不影响另一侧。
     *
     * @param userQuery       用户提问，可空
     * @param assistantAnswer 助手回答，可空
     * @return 「用户：… / 助手：…」两行文本；两侧都空时返回空串
     */
    private static String dialogue(String userQuery, String assistantAnswer) {
        String user = clip(userQuery, SOURCE_LIMIT);
        String answer = clip(assistantAnswer, SOURCE_LIMIT);
        return (user.isEmpty() && answer.isEmpty()) ? "" : "用户：" + user + "\n助手：" + answer;
    }

    /**
     * 记录本池的占用与积压。标题任务出问题时，先看这里判断是模型慢还是池被别的任务占满。
     *
     * @param sessionId 会话，仅用于串起日志
     */
    private void logPoolState(Long sessionId) {
        ThreadPoolExecutor pool = lightTaskExecutor.getThreadPoolExecutor();
        if (pool == null) {
            return;
        }
        log.debug("自动生成会话标题开始 sessionId={} active={} queue={}",
                sessionId, pool.getActiveCount(), pool.getQueue().size());
    }

    /**
     * @param raw 原始文本，可空
     * @param max 最大字符数
     * @return 截断后的文本；空输入返回空串
     */
    private static String clip(String raw, int max) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }
}
