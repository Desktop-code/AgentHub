package com.agenthub.memory.application;

import com.agenthub.common.error.BizException;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.service.MemoryModelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 显式记忆漏斗的 L1：只判定「这句话是不是要求长期记住」。所属域：memory 的 application。
 *
 * <p>为什么单独一层：L0 正则没命中时，不能立刻断定用户不想记忆（「我居住地是广州」）。
 * 但也不能对每句话都跑一次 L2 抽取，那会把闲聊、提问、一次性命令都变成出站。
 * L1 用一颗很小的分类调用把范围收窄：只允许输出 store / normal。
 *
 * <p>模型与提示词约束：
 * <ol>
 *   <li>复用 MEMORY 抽取模型（{@link SummaryModelResolver#requireExtract}），不新增 L1 模型槽；
 *       没有 MEMORY 时按现有解析降 CHAT；</li>
 *   <li>系统段就是本类代码常量，只允许输出 store / normal；<b>不拼</b>平台记忆抽取模板、
 *       <b>不拼</b>MEMORY L3 追加要求，避免分类判断被抽取契约/格式说明带偏；</li>
 *   <li>{@code temperature=0}、{@code max_tokens} 很小，降低采样带来的不确定性。</li>
 * </ol>
 *
 * <p>失败策略：任何异常都按 normal 处理并只打日志——L1 是漏斗的省流层，不能因为它失败
 * 影响主回答，也不能因为失败就把用户的话误写成长期记忆。
 */
@Component
public class MemoryIntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(MemoryIntentClassifier.class);

    /**
     * L1 系统段（代码常量）。用户不可见、不可覆盖。
     */
    static final String INSTRUCTION = """
            你是记忆写入判定器。判断用户当前这句话是否明确要求把某条信息长期记住（而不是让助手当场处理）。
            只输出一个单词：store 或 normal。禁止输出解释、标点、引号、JSON 或任何其它文字。
            store：用户明确说记住、保存、别忘了、以后都按……等要求保留信息。
            normal：陈述事实、提问、闲聊、让助手执行一次性任务、只是复述信息但没有要求记住。
            无法确定时输出 normal。
            """;

    /** 分类调用温度固定 0，避免同一句话在不同采样下判成不同结果 */
    private static final double TEMPERATURE = 0.0d;

    /**
     * 输出上限。取值说明（排障结论）：
     * <p>
     * 早先用 8，推理类 MEMORY 模型会把这点额度全用在 thinking 上，正文一个 token 都没有，
     * 于是拿到「模型没有返回文本」→ 按 normal 落，看起来像「L1 总是判 normal」。
     * 现在固定 512：对「只回一个单词」仍然是上限而不是花费（非推理模型只吐一个词），
     * 但对推理模型足以覆盖「一段思考 + 一个词」。
     * <p>
     * 仍不够时由出站客户端自动再放大到 8 倍（保底 2048，封顶 4096）并关闭 think，重试一次；
     * 重试仍拿不到正文才抛业务异常，本类依旧按 normal 处理 —— 绝不默认成 store。
     */
    static final int MAX_TOKENS = 512;

    /** 只允许进入 L2 的分类结果 */
    private static final String STORE = "store";

    /** 日志用用途标识 */
    static final String PURPOSE = "L1";

    private final MemoryModelClient models;
    private final SummaryModelResolver resolver;

    /**
     * @param models   复用 MEMORY 模型的出站
     * @param resolver 解析 MEMORY / 降级 CHAT（只解析模型，不取任何提示词追加区）
     */
    public MemoryIntentClassifier(MemoryModelClient models, SummaryModelResolver resolver) {
        this.models = models;
        this.resolver = resolver;
    }

    /**
     * 判定是否进入 L2。只应在 {@code memoryTaskExecutor} 池线程上调用。
     *
     * @param policy    池线程上重读的策略
     * @param agentId   Agent
     * @param userId    平台用户
     * @param sessionId 会话，仅用于计量
     * @param text      用户当轮原文
     * @return true=store（继续 L2）；false=normal 或分类失败
     */
    public boolean shouldStore(MemoryPolicy policy, long agentId, long userId, Long sessionId, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        try {
            long modelId = resolver.requireExtract(policy, agentId);
            // L1 只判 store/normal：不拼平台抽取模板、不拼追加要求，系统段就是代码常量
            // 出站前先落一行目标信息：没这行就只能靠「结果日志」反推到底有没有调用
            MemoryOutboundLogs.start(log, PURPOSE, agentId, sessionId, userId, modelId,
                    models.describe(modelId), TEMPERATURE, MAX_TOKENS,
                    INSTRUCTION.length(), text.length());
            String raw = models.complete(agentId, sessionId, userId, modelId,
                    INSTRUCTION, null, text, TEMPERATURE, MAX_TOKENS);
            boolean store = isStore(raw);
            log.info("L1记忆判定 sessionId={} agentId={} modelId={} result={} rawChars={}",
                    sessionId, agentId, modelId, store ? STORE : "normal", raw == null ? 0 : raw.length());
            return store;
        } catch (BizException ex) {
            // 无可用模型 / Key 等业务失败：按 normal 结束，不挡主回答
            log.warn("L1记忆判定失败按normal处理 sessionId={} agentId={} type=BizException msg={}",
                    sessionId, agentId, ex.getMessage());
            return false;
        } catch (RuntimeException ex) {
            log.warn("L1记忆判定异常按normal处理 sessionId={} agentId={} type={} msg={}",
                    sessionId, agentId, ex.getClass().getSimpleName(), ex.getMessage());
            return false;
        }
    }

    /**
     * 严格解析分类结果：去空白/引号/句末标点后必须恰好是 {@code store}。
     * 其它任何输出（含空、normal、带解释的 store…）一律按 normal，避免把格式错误的输出当成写入意图。
     *
     * @param raw 模型原文
     * @return 是否 store
     */
    static boolean isStore(String raw) {
        if (raw == null) {
            return false;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\"'`。.!！?？\\s]", "");
        return STORE.equals(normalized);
    }

}
