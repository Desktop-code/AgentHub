package com.agenthub.runtime.infra.agentscope.builtin;

import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * browserUse：打开取标题、同一会话填表点击、拒绝内网、超时后不沿用旧页。
 * 页面用 classpath 本地 HTML，不启动 Chrome，不访问外网。所属域：runtime。
 */
class BrowserUseTest {

    /**
     * 打开仍能取到标题；同一专家换一轮（会话号尾部纳秒不同）还能接着填、点、读。
     * 验证码只提示，不会被自动填上。
     */
    @Test
    void openThenFillClickAndReadStayOnTheSameSession() throws Exception {
        Harness harness = harness();
        RuntimeContext first = ctx("trial-9-agent-3-1");
        RuntimeContext later = ctx("trial-9-agent-3-99");

        String opened = harness.tool.browserUse("open", "https://example.com/form", null, null, first);
        assertTrue(opened.contains("标题：本地表单"));
        assertTrue(opened.contains("不会自动识别"));
        assertEquals(1, harness.engine.opens.get());

        harness.tool.browserUse("fill", null, "#code", "0421", later);
        String clicked = harness.tool.browserUse("click", null, "#go", null, later);
        assertTrue(clicked.contains("已提交:0421"), clicked);
        String read = harness.tool.browserUse("read", null, null, null, first);
        assertTrue(read.contains("code=0421"), read);
        assertTrue(read.contains("captcha="), read);
        assertFalse(read.contains("captcha=0421"));
        assertEquals(1, harness.engine.opens.get());

        RuntimeContext other = ctx("trial-9-agent-4-1");
        String foreign = harness.tool.browserUse("read", null, null, null, other);
        assertEquals(BrowserSessionStore.NO_PAGE, foreign);
    }

    /** 只传网址、不传动作时仍按打开处理，标题能回来。 */
    @Test
    void urlWithoutActionStillOpensAndReturnsTitle() throws Exception {
        Harness harness = harness();
        String opened = harness.tool.browserUse(null, "https://example.com/form", null, null, ctx("trial-2-agent-1-5"));
        assertTrue(opened.contains("已打开 https://example.com/form"));
        assertTrue(opened.contains("标题：本地表单"));
    }

    /** localhost、.local 和内网 IP 字面量在打开前就被拒绝，不会去创建页面。 */
    @Test
    void rejectsIntranetBeforeOpening() throws Exception {
        Harness harness = harness();
        RuntimeContext ctx = ctx("trial-1-agent-1-1");
        assertEquals("打开网页失败：禁止访问本机或内网地址",
                harness.tool.browserUse("open", "http://127.0.0.1/a", null, null, ctx));
        assertEquals("打开网页失败：禁止访问本机或内网地址",
                harness.tool.browserUse("open", "http://localhost/a", null, null, ctx));
        assertEquals("打开网页失败：禁止访问本机或内网地址",
                harness.tool.browserUse("open", "http://files.local/a", null, null, ctx));
        assertEquals("打开网页失败：禁止访问本机或内网地址",
                harness.tool.browserUse("open", "http://10.0.0.8/a", null, null, ctx));
        assertEquals("打开网页失败：禁止访问本机或内网地址",
                harness.tool.browserUse("open", "http://192.168.1.9/", null, null, ctx));
        assertEquals("打开网页失败：只允许 http/https",
                harness.tool.browserUse("open", "ftp://example.com/a", null, null, ctx));
        assertEquals("打开网页失败：网址不能为空",
                harness.tool.browserUse("open", "  ", null, null, ctx));
        assertEquals(0, harness.engine.opens.get());
    }

    /** 空闲超过上限后，下一次读取不能再看到上次填进去的值。 */
    @Test
    void expiredSessionIsNotReused() throws Exception {
        Harness harness = harness();
        RuntimeContext ctx = ctx("trial-8-agent-2-1");
        harness.tool.browserUse("open", "https://example.com/form", null, null, ctx);
        harness.tool.browserUse("fill", null, "#code", "0421", ctx);
        harness.clock.now += harness.idle.toMillis() + 1;

        String read = harness.tool.browserUse("read", null, null, null, ctx);
        assertEquals(BrowserSessionStore.EXPIRED, read);
        assertFalse(read.contains("0421"));

        String reopened = harness.tool.browserUse("open", "https://example.com/form", null, null, ctx);
        assertTrue(reopened.contains("标题：本地表单"));
        assertFalse(reopened.contains("0421"));
        assertEquals(2, harness.engine.opens.get());
    }

    /** 明确关闭后，同一专家再读也拿不到刚才的页面。 */
    @Test
    void closeDropsTheSession() throws Exception {
        Harness harness = harness();
        RuntimeContext ctx = ctx("trial-8-agent-2-7");
        harness.tool.browserUse("open", "https://example.com/form", null, null, ctx);
        assertEquals(BrowserSessionStore.CLOSED, harness.tool.browserUse("close", null, null, null, ctx));
        assertEquals(BrowserSessionStore.NO_PAGE, harness.tool.browserUse("read", null, null, null, ctx));
    }

    /** 截图交给工作区，并带上「不会自动识别」的提示。 */
    @Test
    void screenshotIsSavedAndDoesNotSolveCaptcha() throws Exception {
        Path root = Files.createTempDirectory("browser-use-shot");
        Harness harness = harness(new WorkspaceSandbox(root.toString()));
        RuntimeContext ctx = ctx("trial-6-agent-1-1");
        harness.tool.browserUse("open", "https://example.com/form", null, null, ctx);
        String shot = harness.tool.browserUse("screenshot", null, null, null, ctx);
        assertTrue(shot.contains("/workspace/browser/shot-"), shot);
        assertTrue(shot.contains("不会自动识别"), shot);
        assertTrue(shot.contains("[FILE_GENERATED]"), shot);
    }

    /** 启动失败、导航超时、连接被拒绝要能区分，不能收成同一句。 */
    @Test
    void failureReasonsStayDistinct() {
        assertEquals("浏览器启动失败：本机 Chrome 未能启动",
                BrowserFailures.explain(new RuntimeException("Failed to launch"), BrowserFailures.Phase.LAUNCH));
        assertEquals("打开网页失败：页面加载超时",
                BrowserFailures.explain(new RuntimeException("Timeout 30000ms exceeded"), BrowserFailures.Phase.NAVIGATE));
        assertEquals("打开网页失败：页面打不开（连接被拒绝）",
                BrowserFailures.explain(new RuntimeException("net::ERR_CONNECTION_REFUSED at https://example.com"),
                        BrowserFailures.Phase.NAVIGATE));
        assertEquals("操作失败：找不到元素或操作超时",
                BrowserFailures.explain(new RuntimeException("Timeout"), BrowserFailures.Phase.ACTION));
    }

    private static Harness harness() throws Exception {
        return harness(null);
    }

    private static Harness harness(WorkspaceSandbox sandbox) throws Exception {
        FixtureBrowserEngine engine = new FixtureBrowserEngine();
        ManualClock clock = new ManualClock();
        Duration idle = Duration.ofMillis(40);
        BrowserSessionStore store = new BrowserSessionStore(engine, Duration.ofSeconds(2), idle, clock, 4, false);
        return new Harness(new BrowserTool(store, sandbox), engine, clock, idle);
    }

    private static RuntimeContext ctx(String sessionId) {
        return RuntimeContext.builder().userId("1").sessionId(sessionId).build();
    }

    private static final class ManualClock implements LongSupplier {
        private long now = 1_000L;

        @Override
        public long getAsLong() {
            return now;
        }
    }

    private static final class Harness {
        private final BrowserTool tool;
        private final FixtureBrowserEngine engine;
        private final ManualClock clock;
        private final Duration idle;

        private Harness(BrowserTool tool, FixtureBrowserEngine engine, ManualClock clock, Duration idle) {
            this.tool = tool;
            this.engine = engine;
            this.clock = clock;
            this.idle = idle;
        }
    }
}
