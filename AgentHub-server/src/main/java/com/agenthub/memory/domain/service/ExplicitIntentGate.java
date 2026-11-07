package com.agenthub.memory.domain.service;

import java.util.regex.Pattern;

/**
 * L1 之前的「值不值得问分类模型」闸门。所属域：memory。
 *
 * <p>为什么需要它：L0 未命中时若对每一句用户话都打一次 L1，等于把每轮闲聊、提问、命令
 * 都变成一次额外出站。这里只用代码内正则挡掉<b>明显不像存储意图</b>的句子：
 * 纯问句、过短且无存储意味、招呼/致谢/确认。放行的句子才进入异步 L1。
 *
 * <p>边界：
 * <ol>
 *   <li>只挡「明显不是」，不做语义判断；像「我居住地是广州」这类陈述句必须放行，
 *       由 L1 输出 normal，而不是在这里猜；</li>
 *   <li>与 L0 不同，这里<b>允许</b>按长度挡短句：L0 已命中的「记住它」不会走到本类；</li>
 *   <li>无状态纯函数，可并发调用。</li>
 * </ol>
 */
public final class ExplicitIntentGate {

    /** 低于该长度且 L0 未命中的句子，视为没有可分类的存储语义 */
    private static final int MIN_CLASSIFY_CHARS = 4;

    /** 以问号收尾 */
    private static final Pattern QUESTION_END = Pattern.compile("[?？]\\s*$");

    /** 以「吗/呢/么/嘛」收尾（允许后面再跟句号/叹号） */
    private static final Pattern QUESTION_TAIL = Pattern.compile("(吗|呢|么|嘛)\\s*[。.!！?？]*\\s*$");

    /** 句中带明显疑问词：没有问号也能识别成提问，避免「我的订单到哪了」被送进 L1 */
    private static final Pattern QUESTION_CUE = Pattern.compile(
            "(哪|什么|怎么|为什么|多少|几点|多久|如何|是否|是不是|能不能|可不可以|有没有)");

    /** 明显的动作指令或疑问开头；这些句子即使不是问号收尾也不需要再问 L1 */
    private static final Pattern NON_STORE_START = Pattern.compile(
            "^(请问|请帮我|帮我查|查一下|查询|搜索|打开|关闭|播放|暂停|翻译|总结|写一|生成|计算|"
                    + "什么|怎么|为什么|哪|谁|多少|几点|多久|如何|是否|是不是|能不能|可不可以|有没有)");

    /** 招呼、致谢、确认：整句匹配才挡，避免「好的，记住我喜欢简洁」被误伤 */
    private static final Pattern SMALL_TALK = Pattern.compile(
            "(?i)^(你好|您好|hi|hello|嗨|谢谢|多谢|好的|好|嗯|ok|收到|明白了|知道了|在吗)"
                    + "[!！。.~～\\s]*$");

    /** 至少含一个汉字/字母/数字；纯标点、纯表情不进入 L1 */
    private static final Pattern HAS_WORD = Pattern.compile(".*[\\p{IsHan}A-Za-z0-9].*");

    private ExplicitIntentGate() {
    }

    /**
     * L0 未命中后，是否值得投递一次 L1 分类。
     *
     * @param text 用户本轮原文
     * @return true 表示放行到 L1；false 表示直接结束本轮显式通道
     */
    public static boolean shouldClassify(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String body = text.trim();
        if (body.length() < MIN_CLASSIFY_CHARS) {
            return false;
        }
        if (!HAS_WORD.matcher(body).matches()) {
            return false;
        }
        if (SMALL_TALK.matcher(body).matches()) {
            return false;
        }
        if (QUESTION_END.matcher(body).find() || QUESTION_TAIL.matcher(body).find()) {
            return false;
        }
        if (QUESTION_CUE.matcher(body).find()) {
            return false;
        }
        return !NON_STORE_START.matcher(body).find();
    }
}
