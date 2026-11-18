package com.agenthub.runtime.infra.agentscope.builtin;

import java.time.Duration;

/**
 * 按网址打开一页。所属域：runtime。
 * 生产用 {@link PlaywrightBrowserEngine}；测试换成本地 HTML，避免访问外网。
 */
public interface BrowserEngine {

    /**
     * @param url     已通过网址闸门的地址
     * @param timeout 启动和首次导航的上限
     * @return 新建的页，调用方负责关闭
     */
    BrowserPagePort open(String url, Duration timeout);
}
