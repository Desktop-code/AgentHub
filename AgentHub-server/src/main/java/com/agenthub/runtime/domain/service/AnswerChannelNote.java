package com.agenthub.runtime.domain.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 工作台叶子专家的【交付通道口径】平台内置锁定文本。所属域：runtime 的 domain。
 *
 * <p>为什么需要这一层：推理模型的思考走独立通道（上游 {@code reasoning_content}），
 * 与回答正文（{@code content}）分开落库、分开展示。模型一旦把方案、代码、表格写进思考通道，
 * 页面上就变成「思考框里是整份方案，结果框只剩一句收尾」——用户点选项续作时最容易踩到：
 * 它把「接着把方案写完」当成了「先在思考里把方案想完」，正文只留一句「点选后我会给出…」。
 * 平台必须在系统提示里锁死通道分工，而不是等正文空了再在前端补。
 *
 * <p>关键约束：
 * <ul>
 *   <li><b>禁止</b>改成 Java 字符串常量：文案随平台发版更新，代码里不保留第二份；</li>
 *   <li><b>不是</b> {@code PromptPurpose} 用途：不进提示词中心用途下拉，也不写入 CHAT 模板段落；</li>
 *   <li>Agent 与页面都不可编辑、不可覆盖；</li>
 *   <li>只给「工作台叶子专家（含点选续作）」装配，判路由模型与多专家汇总不注入。</li>
 * </ul>
 *
 * <p>线程安全：类加载时一次性读取并缓存，之后只读，无状态。
 */
public final class AnswerChannelNote {

    /** classpath 下的平台文案；换语言时按 {@code -zh} 后缀扩展，不在代码里兜底中文 */
    static final String RESOURCE = "prompt/workbench-answer-channel-zh.txt";

    /** 资源缺失或为空属于打包事故：直接失败，避免工作台静默退回「方案写进思考」 */
    private static final String TEXT = load();

    private AnswerChannelNote() {
    }

    /**
     * 取平台内置的交付通道口径正文。
     *
     * @return 固定文案，非空
     */
    public static String text() {
        return TEXT;
    }

    /**
     * 从 classpath 读取文案。资源缺失或正文为空时抛 {@link IllegalStateException}，
     * 让问题在启动阶段暴露，而不是运行到某一轮才发现提示词缺段。
     *
     * @return 去掉首尾空白的正文
     */
    private static String load() {
        try (InputStream input = AnswerChannelNote.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("缺少交付通道口径文案：" + RESOURCE);
            }
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                throw new IllegalStateException("交付通道口径文案为空：" + RESOURCE);
            }
            return text;
        } catch (IOException ex) {
            throw new IllegalStateException("读取交付通道口径文案失败：" + RESOURCE, ex);
        }
    }
}
