package com.agenthub.runtime.domain.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 「把终答收成结构化选项」的整理指令（平台内置锁定文案）。所属域：runtime 的 domain。
 *
 * <p>使用方：{@code WorkbenchChoiceCollector} 在模型没有调用 {@code agenthub_choice} 时，
 * 用它再整理一次终答。文案随平台发版维护，代码里不保留第二份。
 *
 * <p>边界：
 * <ul>
 *   <li>只做「结构整理」，不让模型重新回答问题；</li>
 *   <li>不是 {@code PromptPurpose} 用途，不进提示词中心，也不写入 CHAT 模板段落；</li>
 *   <li>Agent 与页面都不可编辑、不可覆盖。</li>
 * </ul>
 *
 * <p>线程安全：类加载时一次性读取并缓存，之后只读。
 */
public final class ChoiceStructurePrompt {

    /** classpath 下的平台文案；换语言时按 {@code -zh} 后缀扩展，不在代码里兜底中文 */
    static final String RESOURCE = "prompt/workbench-choice-extract-zh.txt";

    /** 资源缺失或为空属于打包事故：启动时直接失败，避免整理阶段静默失效 */
    private static final String TEXT = load();

    private ChoiceStructurePrompt() {
    }

    /**
     * @return 整理指令正文，非空
     */
    public static String text() {
        return TEXT;
    }

    /**
     * 从 classpath 读取文案；缺失或为空抛 {@link IllegalStateException}，让问题在启动阶段暴露。
     *
     * @return 去掉首尾空白的正文
     */
    private static String load() {
        try (InputStream input = ChoiceStructurePrompt.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("缺少选项整理指令：" + RESOURCE);
            }
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                throw new IllegalStateException("选项整理指令为空：" + RESOURCE);
            }
            return text;
        } catch (IOException ex) {
            throw new IllegalStateException("读取选项整理指令失败：" + RESOURCE, ex);
        }
    }
}
