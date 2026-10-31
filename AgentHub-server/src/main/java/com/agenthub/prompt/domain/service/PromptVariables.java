package com.agenthub.prompt.domain.service;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 提示词变量白名单与一次性替换。所属域：prompt。
 * <p>
 * 职责：把模板骨架里的 {@code {{var}}} 换成运行期值，且保证**只扫一遍、永不递归**。
 * <p>
 * 为什么不用「循环 String.replace」或多轮替换引擎：
 * 用户输入、工具回灌、记忆原文往往含 {@code {{...}}} 或 <code>${...}</code> 字面量；
 * 多轮替换会把它们当成占位符再解释一次，等于把「提示词注入」变成「模板注入」。
 * 本类等价于 {@code StringSubstitutor} 关闭递归插值的效果，但扫描过程完全自控：
 * <ol>
 *   <li>只识别模板侧的 {@code {{name}}}；<code>${name}</code> 在模板里按字面量保留（不解释）；</li>
 *   <li>替换值只做「拼进输出缓冲」这一件事，绝不回扫，值内的任何占位符形态一律原样落地；</li>
 *   <li>未赋值的白名单变量直接失败，禁止把 {@code {{var}}} 原文送给模型（严格模式）。</li>
 * </ol>
 * 线程安全：无状态，全部方法为静态纯函数。
 */
public final class PromptVariables {

    /** 本轮用户原文。值侧永不再解析 */
    public static final String USER_QUERY = "user_query";
    /** 平台当前时间，格式 yyyy-MM-dd HH:mm:ss（Asia/Shanghai） */
    public static final String CURRENT_TIME = "current_time";
    /** 会话历史摘要。Runtime 后置注入，本组装默认不提供 */
    public static final String HISTORY = "history";
    /** Router 候选路由表。Runtime 后置注入，本组装默认不提供 */
    public static final String ROUTE_TABLE = "route_table";
    /** Agent 名称 */
    public static final String AGENT_NAME = "agent_name";
    /** 用途码 */
    public static final String PURPOSE = "purpose";
    /** 语言 */
    public static final String LOCALE = "locale";

    /** 允许出现在模板里的变量名白名单；未注册的名字保存期就该被拦住 */
    private static final Set<String> REGISTRY = Set.of(
            USER_QUERY, CURRENT_TIME, HISTORY, ROUTE_TABLE, AGENT_NAME, PURPOSE, LOCALE);

    /** 变量名只允许小写字母开头的小写下划线式，避免模板里写出表达式 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");

    private static final String OPEN = "{{";
    private static final String CLOSE = "}}";

    private PromptVariables() {
    }

    /**
     * @return 变量白名单（只读视图），供管理端插入 Tag 与保存校验共用
     */
    public static Set<String> registry() {
        return REGISTRY;
    }

    /**
     * @param name 变量名
     * @return true 表示已注册
     */
    public static boolean registered(String name) {
        return name != null && REGISTRY.contains(name);
    }

    /**
     * 收集模板里出现过的变量名（按出现顺序去重）。保存期校验与排查用。
     *
     * @param template 模板骨架，可空
     * @return 变量名有序集合；模板为空返回空 map
     */
    public static Map<String, Integer> occurrences(String template) {
        Map<String, Integer> found = new LinkedHashMap<>();
        if (template == null || template.isEmpty()) {
            return found;
        }
        int from = 0;
        while (true) {
            int open = template.indexOf(OPEN, from);
            if (open < 0) {
                return found;
            }
            int close = template.indexOf(CLOSE, open + OPEN.length());
            if (close < 0) {
                return found;
            }
            String name = template.substring(open + OPEN.length(), close).trim();
            if (NAME_PATTERN.matcher(name).matches()) {
                found.merge(name, 1, Integer::sum);
            }
            from = close + CLOSE.length();
        }
    }

    /**
     * 一次性非递归替换。模板侧的 {@code {{name}}} 被换成值；值侧内容原样落地。
     *
     * @param template 模板骨架（L1 + L2 段 + L3 追加区拼接结果）
     * @param values   变量值；只读使用，不会被改写
     * @return 替换后的正文
     * @throws BizException 模板写到未注册变量、写坏占位符，或使用了未赋值的已注册变量
     */
    public static String render(String template, Map<String, String> values) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Map<String, String> safeValues = values == null ? Map.of() : values;
        StringBuilder out = new StringBuilder(template.length() + 64);
        int from = 0;
        while (true) {
            int open = template.indexOf(OPEN, from);
            if (open < 0) {
                out.append(template, from, template.length());
                return out.toString();
            }
            int close = template.indexOf(CLOSE, open + OPEN.length());
            if (close < 0) {
                // 只有开括号没有闭括号：按字面量落地，不算占位符，避免整段提示词被误判为坏模板
                out.append(template, from, template.length());
                return out.toString();
            }
            out.append(template, from, open);
            String name = template.substring(open + OPEN.length(), close).trim();
            out.append(resolve(name, safeValues));
            from = close + CLOSE.length();
        }
    }

    /**
     * 解析单个占位符。白名单外的名字一律失败：模板不该靠未注册变量取数。
     *
     * @param name   占位符内文本
     * @param values 已赋值变量
     * @return 值原文（不做任何再解释）
     * @throws BizException 名字非法、未注册或未赋值
     */
    private static String resolve(String name, Map<String, String> values) {
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new BizException(ErrorCode.PROMPT_UNAVAILABLE,
                    "提示词模板含非法占位符：{{" + name + "}}");
        }
        if (!REGISTRY.contains(name)) {
            throw new BizException(ErrorCode.PROMPT_UNAVAILABLE,
                    "提示词模板含未注册变量：{{" + name + "}}");
        }
        String value = values.get(name);
        if (value == null) {
            // 严格模式：绝不把 {{var}} 原文送给模型，也不伪装成业务「未找到」
            throw new BizException(ErrorCode.PROMPT_UNAVAILABLE,
                    "提示词变量未赋值：" + name);
        }
        return value;
    }
}
