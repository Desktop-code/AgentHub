package com.agenthub.runtime.infra.agentscope.builtin;

import com.agenthub.runtime.domain.service.GeneratedFileMarker;
import com.agenthub.runtime.domain.service.WorkspaceAccessException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置浏览器。所属域：runtime。调用名 browserUse，与 tool_item.name 一致，展示名仍是「打开网页」。
 * <p>
 * 专家只调这一个工具：在同一会话里打开、填表、点击、截图、读取标题和可见文本。
 * 一次调用结束不关浏览器，同一试跑会话里的同一位专家可以接着操作；空闲超时或 close 之后才销毁。
 * 不识别验证码，也不代填登录。网址仍拒绝空值、非 http/https、localhost、.local 和内网 IP 字面量。
 */
@Component
public class BrowserTool {

    /**
     * 与工具项目录 description 同一句。列表直接展示这个字段，这里不再写第二套说明。
     */
    public static final String DESCRIPTION = "在同一浏览器会话里打开公网页面，并可继续填表、点击、截图、读取标题和可见文本。"
            + "不能自动识别验证码，也不能代为登录；遇到验证码会交回截图或提示。"
            + "仅允许公网 http/https，禁止内网。";

    private static final Logger log = LoggerFactory.getLogger(BrowserTool.class);
    /** 试跑运行时会话形如 trial-12-agent-3-纳秒；纳秒每轮都变，会话键只留到专家 */
    private static final Pattern TRIAL_AGENT = Pattern.compile("^trial-(\\d+)-agent-(\\d+)(?:-\\d+)?$");
    /** 回给专家的可见文本上限，避免整页正文挤爆下一轮上下文 */
    private static final int TEXT_LIMIT = 1500;

    private final BrowserSessionStore sessions;
    private final WorkspaceSandbox sandbox;

    /**
     * @param sessions 会话表，按专家保留浏览器
     * @param sandbox  截图落盘；不可用时仍回提示，不把图片字节塞进对话
     */
    @Autowired
    public BrowserTool(BrowserSessionStore sessions, WorkspaceSandbox sandbox) {
        this.sessions = sessions;
        this.sandbox = sandbox;
    }

    /**
     * 只给反射注解的测试用：不启动清扫，也不连接 Chrome。
     *
     * @param opTimeout 占位，会话表仍要一个正的操作上限
     */
    public BrowserTool(Duration opTimeout) {
        this(new BrowserSessionStore(
                (url, timeout) -> {
                    throw new BrowserUseException("未连接浏览器");
                },
                opTimeout == null ? Duration.ofSeconds(30) : opTimeout,
                Duration.ofMinutes(5),
                System::currentTimeMillis,
                1,
                false), null);
    }

    /**
     * 在当前专家的浏览器会话里执行一步。
     *
     * @param action         open、fill、click、screenshot、read、close；空则有网址就打开，否则读当前页
     * @param url            open 时的公网网址
     * @param selector       fill、click 的 CSS 选择器
     * @param value          fill 写入的文本
     * @param runtimeContext 引擎注入的会话，用来区分专家；不进工具参数
     * @return 给专家的中文结果或失败原因
     */
    @Tool(name = "browserUse", description = DESCRIPTION)
    public String browserUse(
            @ToolParam(name = "action",
                    description = "动作：open 打开网址，fill 按选择器填值，click 点击，screenshot 截图，read 读取标题和可见文本，close 关闭会话。不传时：给了 url 就打开，否则读取当前页",
                    required = false) String action,
            @ToolParam(name = "url", description = "open 时要打开的公网网址", required = false) String url,
            @ToolParam(name = "selector", description = "CSS 选择器，fill 和 click 使用", required = false) String selector,
            @ToolParam(name = "value", description = "fill 时写入的文本", required = false) String value,
            RuntimeContext runtimeContext) {
        String key = scopeKey(runtimeContext);
        if (key == null) {
            return "无法绑定浏览器会话：当前调用没有专家会话。";
        }
        String step = actionOf(action, url);
        try {
            return switch (step) {
                case "open" -> open(key, url);
                case "fill" -> fill(key, selector, value);
                case "click" -> click(key, selector);
                case "screenshot" -> shoot(key, runtimeContext);
                case "read" -> format(sessions.read(key));
                case "close" -> close(key);
                default -> "不认识的动作「" + step + "」。请使用 open、fill、click、screenshot、read、close。";
            };
        } catch (BrowserUseException ex) {
            return ex.getMessage();
        } catch (RuntimeException ex) {
            log.warn("[Agent][browserUse] action={} 失败 type={}", step, ex.getClass().getSimpleName(), ex);
            return "打开网页失败：页面打不开";
        }
    }

    /**
     * 试跑会话里同一位专家共用一个浏览器；纳秒后缀去掉，换一轮对话还能接着填。
     *
     * @param runtimeContext 引擎上下文，可空
     * @return 会话键；没有会话标识时返回 null
     */
    static String scopeKey(RuntimeContext runtimeContext) {
        if (runtimeContext == null || runtimeContext.getSessionId() == null
                || runtimeContext.getSessionId().isBlank()) {
            return null;
        }
        String raw = runtimeContext.getSessionId().trim();
        Matcher trial = TRIAL_AGENT.matcher(raw);
        if (trial.matches()) {
            return "trial-" + trial.group(1) + "-agent-" + trial.group(2);
        }
        return raw;
    }

    private String open(String key, String url) {
        String rejected = BrowserUrlPolicy.reject(url);
        if (rejected != null) {
            return rejected;
        }
        BrowserPagePort.PageView view = sessions.open(key, url.trim());
        return "已打开 " + url.trim() + "\n" + format(view);
    }

    private String fill(String key, String selector, String value) {
        if (selector == null || selector.isBlank()) {
            return "填表失败：缺少选择器";
        }
        if (value == null) {
            return "填表失败：缺少要填入的内容";
        }
        BrowserPagePort.PageView view = sessions.fill(key, selector.trim(), value);
        return "已填入 " + selector.trim() + "\n" + format(view);
    }

    private String click(String key, String selector) {
        if (selector == null || selector.isBlank()) {
            return "点击失败：缺少选择器";
        }
        BrowserPagePort.PageView view = sessions.click(key, selector.trim());
        return "已点击 " + selector.trim() + "\n" + format(view);
    }

    private String close(String key) {
        sessions.close(key);
        return BrowserSessionStore.CLOSED;
    }

    private String shoot(String key, RuntimeContext runtimeContext) {
        byte[] png = sessions.screenshot(key);
        BrowserPagePort.PageView view = sessions.read(key);
        String saved = saveShot(runtimeContext, png);
        String body = saved == null
                ? "已截图，但工作区不可用，图片没有交回。请根据下面的页面文字处理。"
                : saved;
        String handoff = view.handoff();
        if (handoff.isEmpty()) {
            return body;
        }
        return body + "\n" + handoff;
    }

    /**
     * 截图只通过已有工作区交回，不把图片字节写进工具结果。
     *
     * @param runtimeContext 用来定位这一轮的工作区
     * @param png            截图像素
     * @return 带虚拟路径和产物标记的说明；写不进去时返回 null
     */
    private String saveShot(RuntimeContext runtimeContext, byte[] png) {
        if (sandbox == null || png == null || png.length == 0 || runtimeContext == null) {
            return null;
        }
        String fileName = "browser/shot-" + System.currentTimeMillis() + ".png";
        try {
            Path root = sandbox.sessionRoot(runtimeContext.getSessionId());
            Path target = sandbox.resolveInside(root, fileName);
            sandbox.rejectExecutable(target.getFileName().toString());
            Files.createDirectories(target.getParent());
            Files.write(target, png);
            String relative = WorkspaceSandbox.relativeOf(root, target);
            String virtual = WorkspaceSandbox.VIRTUAL_PREFIX + relative;
            return "已截图，路径 " + virtual + "。\n" + GeneratedFileMarker.mark(relative, png.length);
        } catch (WorkspaceAccessException | IOException ex) {
            log.warn("[Agent][browserUse] 截图落盘失败 type={}", ex.getClass().getSimpleName());
            return null;
        }
    }

    private static String format(BrowserPagePort.PageView view) {
        String title = view.title() == null || view.title().isBlank() ? "（无）" : view.title().trim();
        String text = view.visibleText() == null ? "" : view.visibleText().trim();
        boolean cut = text.length() > TEXT_LIMIT;
        if (cut) {
            text = text.substring(0, TEXT_LIMIT);
        }
        StringBuilder body = new StringBuilder();
        body.append("标题：").append(title);
        if (!text.isEmpty()) {
            body.append("\n可见文本：\n").append(text);
            if (cut) {
                body.append("\n（可见文本已截断）");
            }
        } else {
            body.append("\n可见文本：（空）");
        }
        String handoff = view.handoff();
        if (!handoff.isEmpty()) {
            body.append('\n').append(handoff);
        }
        return body.toString();
    }

    /**
     * 没指定动作时：有网址就打开，否则读取当前页。这是参数缺省，不是某个网站的特例。
     *
     * @param action 模型给的动作，可空
     * @param url    模型给的网址，可空
     * @return 规范化后的动作名；无法识别时原样返回，交给调用处提示
     */
    private static String actionOf(String action, String url) {
        String raw = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) {
            return url == null || url.isBlank() ? "read" : "open";
        }
        return switch (raw) {
            case "open", "fill", "click", "screenshot", "read", "close" -> raw;
            case "shot", "capture" -> "screenshot";
            default -> raw;
        };
    }
}
