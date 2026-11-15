package com.agenthub.runtime.domain.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 断点续作用的「用户侧输入」组装：原问句 + 上一轮终答 + 所选选项 + 平台锁定要求。所属域：runtime 的 domain。
 *
 * <p>为什么必须有这一层：用户点 chip 只是「对上一问的回答」。
 * 只把选项文案送给模型，它收到的是一句孤立的短语（例如「Vue3 + Vite + TS」），
 * 于是要么回一句「已完成作答」，要么把问题再问一遍——都不会给出完整方案。
 * 续作必须把上下文一起交回给同一位专家。
 *
 * <p>为什么不用会话窗口预览（{@code previewUser}）代替：窗口是「最近若干轮」的滚动摘要，
 * 可能已被截断或尚未写入；而续作需要的是**确定**的那一轮问句与终答。
 * 这里直接用已落库事件组装，口径稳定、可测。
 *
 * <p>约束：
 * <ul>
 *   <li>平台锁定要求放在 classpath 资源里，随发版更新，代码内不保留第二份；</li>
 *   <li>不做任何模型调用、不查库：纯字符串组装，便于单测；</li>
 *   <li>只做长度裁剪，不改写、不总结用户的问句与专家的终答。</li>
 * </ul>
 *
 * <p>线程安全：类加载时一次性读取并缓存锁定文案，之后只读。
 */
public final class ChoiceContinue {

    /** 平台锁定的续作要求（「接着做完、给完整方案」） */
    static final String RESOURCE = "prompt/workbench-choice-continue-zh.txt";

    /** 原问句的保留上限：问句通常很短，超长只会是粘进来的大段文本 */
    private static final int QUESTION_LIMIT = 2000;

    /** 上一轮终答的保留上限：方案正文可能很长，但要给本轮留足生成预算 */
    private static final int ANSWER_LIMIT = 6000;

    /** 资源缺失或为空属于打包事故：启动时直接失败，避免续作静默退化成「只喂一句选项」 */
    private static final String NOTE = load();

    private ChoiceContinue() {
    }

    /**
     * 组装续作轮送给专家的用户侧文本。
     *
     * <p>段落顺序固定：先事实（上一轮问题 → 上一轮回答 → 用户选择），后要求。
     * 缺任何一段就整段不出现，不留空标题。
     *
     * @param question 上一轮的用户问句，可空
     * @param answer   上一轮的助手终答，可空
     * @param label    用户选中的选项文案，非空（服务端登记值）
     * @return 非空的续作用户文本
     */
    public static String text(String question, String answer, String label) {
        StringBuilder block = new StringBuilder();
        appendSection(block, "【上一轮的问题】", clip(question, QUESTION_LIMIT));
        appendSection(block, "【上一轮的完整回答】", clip(answer, ANSWER_LIMIT));
        appendSection(block, "【用户刚刚选择】", clip(label, QUESTION_LIMIT));
        // 锁定要求永远在最后：它是对上面这段事实的处理指令，不能被前文挤到中间
        if (block.length() > 0) {
            block.append('\n');
        }
        block.append(NOTE);
        return block.toString();
    }

    /**
     * 追加一段「标题 + 正文」。正文为空时整段跳过，避免出现只有标题的空段。
     *
     * @param block 目标缓冲
     * @param title 段标题
     * @param body  段正文，可空
     */
    private static void appendSection(StringBuilder block, String title, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        if (block.length() > 0) {
            block.append('\n');
        }
        block.append(title).append('\n').append(body.trim());
    }

    /**
     * 按字符上限裁剪，超长时补省略号提示「后面还有内容被截了」。
     *
     * @param raw   原文，可空
     * @param limit 上限（必须为正）
     * @return 原文或裁剪结果；空值原样返回
     */
    private static String clip(String raw, int limit) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }

    /**
     * 读取平台锁定文案；缺失或为空抛 {@link IllegalStateException}，让问题在启动阶段暴露。
     *
     * @return 去掉首尾空白的正文
     */
    private static String load() {
        try (InputStream input = ChoiceContinue.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("缺少续作要求文案：" + RESOURCE);
            }
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                throw new IllegalStateException("续作要求文案为空：" + RESOURCE);
            }
            return text;
        } catch (IOException ex) {
            throw new IllegalStateException("读取续作要求文案失败：" + RESOURCE, ex);
        }
    }
}
