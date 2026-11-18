package com.agenthub.runtime.infra.agentscope.builtin;

import java.time.Duration;

/**
 * 一次浏览器会话里的当前页。所属域：runtime。
 * 生产实现是无头 Chrome；测试用本地 HTML。调用方保证同一页的方法都在创建它的那条线程上执行。
 */
public interface BrowserPagePort {

    /**
     * 在已有会话里打开另一个网址，不新开浏览器。
     *
     * @param url     已通过 {@link BrowserUrlPolicy} 的地址
     * @param timeout 这一次导航的上限
     */
    void navigate(String url, Duration timeout);

    /**
     * @return 标题、可见文本，以及是否看到验证码或密码框
     */
    PageView read();

    /**
     * @param selector CSS 选择器
     * @param value    写入的文本，空串表示清空
     * @param timeout  等待元素出现的上限
     */
    void fill(String selector, String value, Duration timeout);

    /**
     * @param selector CSS 选择器
     * @param timeout  等待元素可点的上限
     */
    void click(String selector, Duration timeout);

    /**
     * @return 当前视口截图的字节；失败抛 {@link BrowserUseException}
     */
    byte[] screenshot();

    /** 关掉这一页占用的浏览器进程。可重复调用。 */
    void close();

    /**
     * 读页结果。密码值不放进可见文本。
     *
     * @param title       document.title，可空
     * @param visibleText 可见文字和已填字段，可空
     * @param captcha     页面上有验证码迹象
     * @param password    页面上有密码框
     */
    record PageView(String title, String visibleText, boolean captcha, boolean password) {

        /**
         * @return 提醒专家自己处理验证码或登录；两者都没有时为空串
         */
        public String handoff() {
            StringBuilder text = new StringBuilder();
            if (captcha) {
                text.append("检测到验证码。本工具不会自动识别，请让用户查看截图或页面文字后提供验证码，再用 fill 填入。");
            }
            if (password) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append("检测到密码框。本工具不会自动登录，需要用户明确提供后再 fill。");
            }
            return text.toString();
        }
    }
}
