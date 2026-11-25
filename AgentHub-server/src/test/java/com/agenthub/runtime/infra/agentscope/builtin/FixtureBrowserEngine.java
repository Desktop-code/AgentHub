package com.agenthub.runtime.infra.agentscope.builtin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用 classpath 上的本地 HTML 充当页面，不启动 Chrome、不访问外网。
 * 所属域：runtime 的测试。选择器只认测试页上的 #id。
 */
final class FixtureBrowserEngine implements BrowserEngine {

    private static final Pattern TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern INPUT = Pattern.compile("<input\\s+([^>]+)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile("([A-Za-z0-9:-]+)\\s*=\\s*['\"]([^'\"]*)['\"]");

    final AtomicInteger opens = new AtomicInteger();
    private final String html;

    FixtureBrowserEngine() throws IOException {
        try (InputStream in = FixtureBrowserEngine.class.getResourceAsStream("/browser/local-form.html")) {
            if (in == null) {
                throw new IOException("缺少本地测试页 /browser/local-form.html");
            }
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Override
    public BrowserPagePort open(String url, Duration timeout) {
        opens.incrementAndGet();
        return FixturePage.parse(html);
    }

    /**
     * 本地表单页。点击 #go 把代码写进结果区，模拟提交，不识别验证码。
     */
    private static final class FixturePage implements BrowserPagePort {

        private final String html;
        private final String title;
        private final Map<String, String> inputs;
        private final boolean captcha;
        private final boolean password;
        private String out = "";

        private FixturePage(String html, String title, Map<String, String> inputs, boolean captcha, boolean password) {
            this.html = html;
            this.title = title;
            this.inputs = inputs;
            this.captcha = captcha;
            this.password = password;
        }

        static FixturePage parse(String html) {
            Matcher title = TITLE.matcher(html);
            String text = title.find() ? title.group(1).trim() : "";
            Map<String, String> inputs = new LinkedHashMap<>();
            Matcher input = INPUT.matcher(html);
            while (input.find()) {
                Map<String, String> attrs = attrs(input.group(1));
                String id = attrs.getOrDefault("id", attrs.get("name"));
                if (id != null) {
                    inputs.put(id, attrs.getOrDefault("value", ""));
                }
            }
            String lower = html.toLowerCase();
            return new FixturePage(html, text, inputs, lower.contains("验证码") || lower.contains("captcha"),
                    lower.contains("type=\"password\"") || lower.contains("type='password'"));
        }

        @Override
        public void navigate(String url, Duration timeout) {
            // 同一会话里再次打开：换文档，已填的值不带过去
            FixturePage fresh = parse(html);
            inputs.clear();
            inputs.putAll(fresh.inputs);
            out = "";
        }

        @Override
        public PageView read() {
            StringBuilder text = new StringBuilder("代码\n验证码\n提交");
            if (!out.isEmpty()) {
                text.append('\n').append(out);
            }
            text.append("\n已填字段：");
            boolean first = true;
            for (Map.Entry<String, String> field : inputs.entrySet()) {
                if (!first) {
                    text.append('，');
                }
                first = false;
                text.append(field.getKey()).append('=').append(field.getValue() == null ? "" : field.getValue());
            }
            return new PageView(title, text.toString(), captcha, password);
        }

        @Override
        public void fill(String selector, String value, Duration timeout) {
            String id = idOf(selector);
            if (!inputs.containsKey(id)) {
                throw new BrowserUseException("操作失败：找不到元素或操作超时");
            }
            inputs.put(id, value == null ? "" : value);
        }

        @Override
        public void click(String selector, Duration timeout) {
            String id = idOf(selector);
            if ("go".equals(id)) {
                out = "已提交:" + inputs.getOrDefault("code", "");
                return;
            }
            if (!inputs.containsKey(id)) {
                throw new BrowserUseException("操作失败：找不到元素或操作超时");
            }
        }

        @Override
        public byte[] screenshot() {
            return new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47};
        }

        @Override
        public void close() {
            inputs.clear();
            out = "";
        }

        private static String idOf(String selector) {
            String raw = selector == null ? "" : selector.trim();
            if (raw.startsWith("#")) {
                return raw.substring(1);
            }
            return raw;
        }

        private static Map<String, String> attrs(String raw) {
            Map<String, String> found = new LinkedHashMap<>();
            Matcher matcher = ATTR.matcher(raw);
            while (matcher.find()) {
                found.put(matcher.group(1).toLowerCase(), matcher.group(2));
            }
            return found;
        }
    }
}
