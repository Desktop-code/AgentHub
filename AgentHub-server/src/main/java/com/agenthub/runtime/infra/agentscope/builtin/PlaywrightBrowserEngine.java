package com.agenthub.runtime.infra.agentscope.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.TimeoutError;

import java.time.Duration;

/**
 * 用本机 Google Chrome 的无头模式打开页面。所属域：runtime。
 * 一个 {@link BrowserPagePort} 持有自己的 Playwright 和 Browser，会话结束时由持有者关闭。
 * 必须在创建它的同一条线程上继续调用，Store 用单线程执行器保证这一点。
 */
public final class PlaywrightBrowserEngine implements BrowserEngine {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 一次求值拿到标题、可见文本和验证码/密码迹象。密码值不回传。
     * 验证码只看常见字样，不识别图片内容。
     */
    private static final String READ_JS = """
            () => {
              const body = document.body ? document.body.innerText : '';
              const nodes = document.querySelectorAll('input, textarea, select, img, button, label, canvas');
              let captcha = /验证码|captcha|verifycode/i.test(body);
              let password = false;
              const fields = [];
              nodes.forEach((el) => {
                const blob = [el.id, el.name, el.getAttribute('alt'), el.getAttribute('placeholder'), el.innerText]
                  .filter(Boolean).join(' ');
                if (/验证码|captcha|verifycode/i.test(blob)) captcha = true;
                const type = (el.getAttribute('type') || '').toLowerCase();
                if (type === 'password') password = true;
                if (el.matches && el.matches('input, textarea, select') && type !== 'hidden') {
                  const name = el.id || el.name || el.getAttribute('placeholder') || 'field';
                  fields.push(type === 'password' ? (name + '=（已填写，不回显密码）') : (name + '=' + (el.value || '')));
                }
              });
              const text = fields.length ? body + '\\n已填字段：' + fields.join('，') : body;
              return JSON.stringify({ title: document.title || '', text, captcha, password });
            }
            """;

    /** 生产环境每次打开都新起一套 Playwright。测试不走这个类。 */
    @Override
    public BrowserPagePort open(String url, Duration timeout) {
        Playwright playwright = null;
        Browser browser = null;
        double timeoutMs = timeout.toMillis();
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setChannel("chrome")
                    .setHeadless(true)
                    .setTimeout(timeoutMs));
        } catch (RuntimeException ex) {
            closeQuietly(browser, playwright);
            throw new BrowserUseException(BrowserFailures.explain(ex, BrowserFailures.Phase.LAUNCH));
        }
        try {
            Page page = browser.newPage();
            page.setViewportSize(1280, 720);
            page.setDefaultTimeout(timeoutMs);
            page.setDefaultNavigationTimeout(timeoutMs);
            page.navigate(url, new Page.NavigateOptions().setTimeout(timeoutMs));
            return new PwPage(playwright, browser, page);
        } catch (RuntimeException ex) {
            // 浏览器已经起来，但这一页没打开：进程不能留着，原因按导航失败说
            closeQuietly(browser, playwright);
            throw new BrowserUseException(BrowserFailures.explain(ex, BrowserFailures.Phase.NAVIGATE));
        }
    }

    private static void closeQuietly(Browser browser, Playwright playwright) {
        if (browser != null) {
            try {
                browser.close();
            } catch (RuntimeException ignored) {
                // 启动失败时的清理，不能盖住原来的原因
            }
        }
        if (playwright != null) {
            try {
                playwright.close();
            } catch (RuntimeException ignored) {
                // 同上
            }
        }
    }

    /**
     * 已启动的一页。关闭时连同浏览器进程一起收掉，避免无头 Chrome 残留。
     */
    private static final class PwPage implements BrowserPagePort {

        private final Playwright playwright;
        private final Browser browser;
        private final Page page;
        private boolean closed;

        private PwPage(Playwright playwright, Browser browser, Page page) {
            this.playwright = playwright;
            this.browser = browser;
            this.page = page;
        }

        @Override
        public void navigate(String url, Duration timeout) {
            try {
                page.navigate(url, new Page.NavigateOptions().setTimeout(timeout.toMillis()));
            } catch (RuntimeException ex) {
                throw new BrowserUseException(BrowserFailures.explain(ex, BrowserFailures.Phase.NAVIGATE));
            }
        }

        @Override
        public PageView read() {
            try {
                Object raw = page.evaluate(READ_JS);
                JsonNode node = JSON.readTree(raw == null ? "{}" : raw.toString());
                return new PageView(
                        node.path("title").asText(""),
                        node.path("text").asText(""),
                        node.path("captcha").asBoolean(false),
                        node.path("password").asBoolean(false));
            } catch (Exception ex) {
                throw new BrowserUseException(BrowserFailures.explain(ex, BrowserFailures.Phase.ACTION));
            }
        }

        @Override
        public void fill(String selector, String value, Duration timeout) {
            try {
                page.fill(selector, value == null ? "" : value,
                        new Page.FillOptions().setTimeout(timeout.toMillis()));
            } catch (TimeoutError ex) {
                throw new BrowserUseException("操作失败：找不到元素或操作超时");
            } catch (RuntimeException ex) {
                throw new BrowserUseException(BrowserFailures.explain(ex, BrowserFailures.Phase.ACTION));
            }
        }

        @Override
        public void click(String selector, Duration timeout) {
            try {
                page.click(selector, new Page.ClickOptions().setTimeout(timeout.toMillis()));
            } catch (TimeoutError ex) {
                throw new BrowserUseException("操作失败：找不到元素或操作超时");
            } catch (RuntimeException ex) {
                throw new BrowserUseException(BrowserFailures.explain(ex, BrowserFailures.Phase.ACTION));
            }
        }

        @Override
        public byte[] screenshot() {
            try {
                return page.screenshot(new Page.ScreenshotOptions().setFullPage(false));
            } catch (RuntimeException ex) {
                throw new BrowserUseException(BrowserFailures.explain(ex, BrowserFailures.Phase.ACTION));
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            closeQuietly(browser, playwright);
        }
    }
}
