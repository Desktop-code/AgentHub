package com.agenthub.memory.domain.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 记忆「纠错」信号的窄口径识别。所属域：memory 的 domain。
 * <p>
 * 为什么需要它：用户打错字（「爱吃评估」）后，正确值往往只出现在<b>助手</b>的追问里
 * （「你是想说苹果吗」）。按产品拍板，助手纠正本身<b>不</b>写库，只有两条路能落地：
 * <ol>
 *   <li><b>用户侧更正</b>：当轮用户文本明确在纠正（「不是评估是苹果」「打错了」「纠正一下」）；</li>
 *   <li><b>用户确认</b>：用户明确认下助手给出的正确值（「对，是苹果」），之后走正常抽取覆盖。</li>
 * </ol>
 * 两路都只是「让这句话进抽取」，<b>不</b>直接写库，也<b>不</b>放宽 L1 全局门槛。
 * <p>
 * 刻意「宁准勿滥」：只认少量强特征短语，不猜语义。代价是漏掉一些口语化更正，
 * 换来的是不会把闲聊、提问、反问误当更正去触发一次出站。
 * <p>
 * 另有 {@link #stripAssistantCorrections(String)}：批抽（会话结束 / 摘要顺带）的正文里
 * 助手轮是明文拼进去的，抽取模型可能把助手的「你是想说苹果吗」当成用户偏好写库。
 * 这里在出站前把这类助手纠错独白剔掉，从源头保证「助手单方面纠错不进库」。
 * <p>
 * 无状态纯函数，可并发调用。
 */
public final class MemoryCorrectionSignal {

    private static final Logger log = LoggerFactory.getLogger(MemoryCorrectionSignal.class);

    /**
     * 纠错信号类别。枚举值含义：
     * <ul>
     *   <li>{@link #CORRECTION}：用户在本轮更正先前说错的内容；</li>
     *   <li>{@link #CONFIRMATION}：用户确认上一轮助手提出的正确值。</li>
     * </ul>
     */
    public enum Kind {
        /** 用户正向更正（打错字 / 不是A是B / 纠正一下） */
        CORRECTION,
        /** 用户确认助手给出的值（对，是苹果 / 没错，就是苹果） */
        CONFIRMATION
    }

    /** 与 {@link MemoryTexts#renderTurns(List)} 保持一致的轮次前缀，用于按轮切分正文 */
    private static final String USER_PREFIX = "用户：";
    private static final String ASSISTANT_PREFIX = "助手：";

    /** 纯问句 / 反问：更正与确认都是陈述语气，问句一律不认 */
    private static final Pattern QUESTION_TAIL = Pattern.compile("[?？]\\s*$|(吗|呢|么)\\s*[。.!！]*\\s*$");

    /** 反问式否定：「不是吧」「不是吗」不是更正 */
    private static final List<Pattern> CORRECTION_EXCLUSION = List.of(
            Pattern.compile("不是吧"),
            Pattern.compile("不是吗"),
            Pattern.compile("不都是")
    );

    /**
     * 用户更正特征。全部是强特征，命中任一即算：
     * <ol>
     *   <li>「不是A是B / 不是A而是B」：A、B 之间不跨标点，限 1~20 字；</li>
     *   <li>「我说的是…」「我是说…」「我的意思是…」；</li>
     *   <li>「打错（字）」「写错」「拼错」「输错」；</li>
     *   <li>「纠正一下 / 更正一下 / 修正一下」；</li>
     *   <li>「刚才说错」「说错了」「记错了」「搞错了」。</li>
     * </ol>
     */
    private static final List<Pattern> CORRECTION = List.of(
            Pattern.compile("(?<!是)不是[^，。；,;!！?？\\n]{1,20}(?:而是|是)"),
            Pattern.compile("我说的是|我是说|我的意思是|我说的不是"),
            Pattern.compile("打错|写错|拼错|输错|录错"),
            Pattern.compile("(?:更正|纠正|修正)(?:一下|下|刚才|之前)"),
            Pattern.compile("(?:刚才|之前|前面)(?:我)?说错"),
            Pattern.compile("(?:说|记|搞|听|念)错(?:了|字)")
    );

    /**
     * 用户确认特征：必须带具体值，光「是的 / 好的 / 嗯」不算。
     * <ol>
     *   <li>「对，是苹果」「嗯，就是苹果」「没错，是苹果」；</li>
     *   <li>「对，我说的就是苹果」；</li>
     *   <li>「就是苹果，没错」。</li>
     * </ol>
     * {@code (?!的)} 用来挡掉「是的」「是对的」这类纯应答。
     */
    private static final List<Pattern> CONFIRMATION = List.of(
            Pattern.compile("^(?:对|嗯|呃|没错|是的|对的)[，,、\\s]*(?:就)?是(?!的)[^?？吗呢\\s].{0,20}$"),
            Pattern.compile("^(?:对|嗯|没错)[，,、\\s]*我(?:说的)?就是.{1,20}$"),
            Pattern.compile("^就是.{1,20}[，,]\\s*(?:没错|对的)$")
    );

    /**
     * 助手「纠正 / 澄清」独白的特征。批抽正文里出现这类轮次时整轮剔除，
     * 避免模型把助手的猜测当用户偏好写进库。
     */
    private static final List<Pattern> ASSISTANT_CORRECTION = List.of(
            Pattern.compile("你是想|你想说|你是说|你是指|你的意思是|你是不是想|是不是想说|是不是指"),
            Pattern.compile("我理解(?:错|成)|刚才理解错"),
            Pattern.compile("(?:更正|纠正)(?:一下|下)")
    );

    private MemoryCorrectionSignal() {
    }

    /**
     * 识别当轮用户文本里的纠错意图。
     * <p>
     * 先判排除（问句、反问式否定），再判确认（更具体），最后判更正。
     * 只做规则判断，不调模型；返回空表示「没有纠错意图」。
     *
     * @param text 用户当轮原文，可空
     * @return 纠错信号；空表示不是纠错 / 确认
     */
    public static Optional<Kind> detectUser(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String body = text.trim();
        if (QUESTION_TAIL.matcher(body).find()) {
            return Optional.empty();
        }
        for (Pattern pattern : CORRECTION_EXCLUSION) {
            if (pattern.matcher(body).find()) {
                return Optional.empty();
            }
        }
        for (Pattern pattern : CONFIRMATION) {
            if (pattern.matcher(body).find()) {
                return Optional.of(Kind.CONFIRMATION);
            }
        }
        for (Pattern pattern : CORRECTION) {
            if (pattern.matcher(body).find()) {
                return Optional.of(Kind.CORRECTION);
            }
        }
        return Optional.empty();
    }

    /**
     * 单条助手文本是否像「纠错 / 澄清用户说法」的独白。
     *
     * @param assistantText 助手单轮正文，可空
     * @return true 表示这类内容不应作为记忆证据
     */
    public static boolean isAssistantCorrection(String assistantText) {
        if (assistantText == null || assistantText.isBlank()) {
            return false;
        }
        for (Pattern pattern : ASSISTANT_CORRECTION) {
            if (pattern.matcher(assistantText).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 剔除批抽正文里的助手纠错轮。
     * <p>
     * 按 {@code 用户：} / {@code 助手：} 行首切轮；助手轮命中
     * {@link #isAssistantCorrection(String)} 时整轮丢弃（含该轮的多行正文），
     * 用户轮与其它助手轮原样保留。这样模型只看到用户真正说过的内容，
     * 「助手说你是想说苹果」不会再变成一条偏好。
     *
     * @param transcript 已用 {@link MemoryTexts#renderTurns(List)} 渲染的正文，可空
     * @return 剔除后的正文；无改动时原样返回
     */
    public static String stripAssistantCorrections(String transcript) {
        if (transcript == null || transcript.isBlank()) {
            return transcript == null ? "" : transcript;
        }
        String[] lines = transcript.split("\n", -1);
        StringBuilder kept = new StringBuilder(transcript.length());
        int dropped = 0;
        // dropping 表示「当前轮是助手纠错轮」：它的续行（多行正文）也要一起丢
        boolean dropping = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith(USER_PREFIX)) {
                dropping = false;
            } else if (trimmed.startsWith(ASSISTANT_PREFIX)) {
                dropping = isAssistantCorrection(trimmed.substring(ASSISTANT_PREFIX.length()));
                if (dropping) {
                    dropped++;
                }
            }
            if (!dropping) {
                kept.append(line).append('\n');
            }
        }
        if (dropped == 0) {
            return transcript;
        }
        // 只留痕「剔除过几段」，不把助手原文写进日志（可能是长段闲聊）
        log.info("[MEMORY] 批抽剔除助手纠错独白 rounds={}", dropped);
        return kept.toString();
    }

    /**
     * 纠错信号 → 送给抽取模型的提示行。
     * <p>
     * 只影响当次出站的正文首行，不改平台 MEMORY 模板的锁定段：
     * 模板里已有「用户更正已有事实时必须复用原事实的 fact_key」，
     * 这里再显式点一次，提高「换说法的同族 key」被归到同一 canonical 的概率。
     *
     * @param kind 纠错类别，可空
     * @return 提示行（带换行）；无信号时为空串
     */
    public static String hint(Kind kind) {
        if (kind == Kind.CORRECTION) {
            return "【用户更正】用户本轮在更正先前说错 / 打错的内容：请复用该事实原有的 fact_key，"
                    + "content 只写更正后的最新结论，禁止另造同义 key。\n";
        }
        if (kind == Kind.CONFIRMATION) {
            return "【用户确认】用户本轮确认了助手提出的说法：请按用户确认的值抽取，"
                    + "复用该事实原有的 fact_key，禁止另造同义 key。\n";
        }
        return "";
    }
}
