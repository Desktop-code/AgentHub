package com.agenthub.runtime.infra.agentscope.builtin;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;

/**
 * 浏览器工具（Playwright headless Chrome）。@Tool(name) 须与 tool_item.name 一致：browserUse。
 * 自测可用；默认种子/开源勿默认依赖 Playwright。
 */
@Component
public class BrowserTool {

    private static final Logger log = LoggerFactory.getLogger(BrowserTool.class);

    private final Duration browserTimeout;

    public BrowserTool(
            @Value("${agenthub.runtime.browser-timeout:30s}") Duration browserTimeout) {
        this.browserTimeout = browserTimeout == null ? Duration.ofSeconds(30) : browserTimeout;
    }

    @Tool(
            name = "browserUse",
            description = "打开指定网页并返回标题。仅允许公网 http/https，禁止内网。有超时。")
    public String browserUse(
            @ToolParam(name = "url", description = "要打开的网址") String url) {
        String blocked = validateUrl(url);
        if (blocked != null) {
            return blocked;
        }
        double timeoutMs = browserTimeout.toMillis();
        Playwright playwright = null;
        Browser browser = null;
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                            .setChannel("chrome")
                            .setHeadless(true)
                            .setTimeout(timeoutMs));
            Page page = browser.newPage();
            page.setDefaultTimeout(timeoutMs);
            page.setDefaultNavigationTimeout(timeoutMs);
            page.navigate(url.trim(), new Page.NavigateOptions().setTimeout(timeoutMs));
            String title = page.title();
            return StringUtils.hasText(title) ? title : "页面已打开，但没有标题";
        } catch (Exception e) {
            // Playwright 的异常原文含本机 Chrome 路径与驱动版本，一律只进日志
            log.warn("[Agent][browserUse] url={} 失败 err={}", url, e.getMessage(), e);
            return "浏览器暂时不可用，请稍后再试。";
        } finally {
            if (browser != null) {
                try {
                    browser.close();
                } catch (Exception ignored) {
                    // 关闭失败不影响主流程
                }
            }
            if (playwright != null) {
                try {
                    playwright.close();
                } catch (Exception ignored) {
                    // 同上
                }
            }
        }
    }

    static String validateUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return "打开网页失败：网址不能为空";
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            return "打开网页失败：网址格式不正确";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return "打开网页失败：只允许 http/https";
        }
        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            return "打开网页失败：缺少主机名";
        }
        String lower = host.toLowerCase();
        if ("localhost".equals(lower) || lower.endsWith(".local") || "0.0.0.0".equals(lower)) {
            return "打开网页失败：禁止访问本机或内网地址";
        }
        if (isIpLiteral(lower)) {
            try {
                InetAddress address = InetAddress.getByName(host);
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
                    return "打开网页失败：禁止访问本机或内网地址";
                }
            } catch (Exception e) {
                return "打开网页失败：无法解析主机名";
            }
        }
        return null;
    }

    static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            try {
                int n = Integer.parseInt(part);
                if (n < 0 || n > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }
}
