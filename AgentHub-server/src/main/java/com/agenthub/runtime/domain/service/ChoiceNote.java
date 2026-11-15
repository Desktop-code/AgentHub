package com.agenthub.runtime.domain.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 主对话系统提示里的【需要用户拍板时的选项块】平台内置锁定文本。所属域：runtime 的 domain。
 *
 * <p>职责：只从 classpath 资源读取平台随发版维护的固定文案，供
 * {@code TrialPromptFactory} 在工作台叶子专家装配时拼进系统提示。
 *
 * <p>关键约束：
 * <ul>
 *   <li><b>禁止</b>改成 Java 字符串常量：文案必须随平台发版更新，代码里不保留第二份；</li>
 *   <li><b>不是</b> {@code PromptPurpose} 用途：不进提示词中心用途下拉，也不写入 CHAT 模板段落；</li>
 *   <li>Agent 与页面都不可编辑、不可覆盖；</li>
 *   <li>只给「工作台单专家 / 入口直分叶子」装配，判路由模型与多专家汇总不注入。</li>
 * </ul>
 * <p>
 * 线程安全：类加载时一次性读取并缓存，之后只读，无状态。
 */
public final class ChoiceNote {

    /** classpath 下的平台文案；换语言时按 {@code -zh} 后缀扩展，不在代码里兜底中文 */
    static final String RESOURCE = "prompt/workbench-choice-note-zh.txt";

    /** 资源缺失或为空属于打包事故：直接失败，避免工作台静默少掉可点选项能力 */
    private static final String TEXT = load();

    private ChoiceNote() {
    }

    /**
     * 取平台内置的选项块说明正文（已带段落标记）。
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
        try (InputStream input = ChoiceNote.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("缺少平台内置选项块说明：" + RESOURCE);
            }
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                throw new IllegalStateException("平台内置选项块说明为空：" + RESOURCE);
            }
            return text;
        } catch (IOException ex) {
            throw new IllegalStateException("读取平台内置选项块说明失败：" + RESOURCE, ex);
        }
    }
}
