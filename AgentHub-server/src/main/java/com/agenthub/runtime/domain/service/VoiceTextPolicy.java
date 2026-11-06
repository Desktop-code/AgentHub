package com.agenthub.runtime.domain.service;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;

/**
 * 语音朗读的文字约束。所属域：runtime。
 *
 * <p>为什么要有上限：语音接口按字符计费（且多数实现单次输入有硬上限），
 * 把一篇几万字的回答整段发过去只会被上游拒绝或多花一笔钱。
 * 超限时明确报错，前端据此改用浏览器朗读——浏览器那头是没有字符上限的，
 * 用户仍然能听完整段回答，不会因为模型侧限制而失去功能。
 *
 * <p>线程：无状态，静态纯函数。
 */
public final class VoiceTextPolicy {

    private VoiceTextPolicy() {
    }

    /**
     * 校验并规范化待朗读文字。
     *
     * @param raw      原始文字（前端已转成纯文本）
     * @param maxChars 字符上限（含）
     * @return 去除首尾空白后的文字
     * @throws BizException 空文字或超限
     */
    public static String requireSpeakable(String raw, int maxChars) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "这条回答没有可朗读的内容");
        }
        if (text.length() > maxChars) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "回答超过 " + maxChars + " 字，语音合成已跳过，请改用浏览器朗读");
        }
        return text;
    }
}
