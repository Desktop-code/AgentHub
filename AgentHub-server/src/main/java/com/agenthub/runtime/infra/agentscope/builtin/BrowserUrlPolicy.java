package com.agenthub.runtime.infra.agentscope.builtin;

import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.URI;

/**
 * browserUse 的网址闸门。所属域：runtime。
 * 只拒绝空地址、非 http/https，以及 localhost、.local 和内网 IP 字面量。
 * 普通域名不做 DNS 解析，跳转后的地址也不在这里复查。
 */
public final class BrowserUrlPolicy {

    private BrowserUrlPolicy() {
    }

    /**
     * @param url 模型传入的网址，可空
     * @return 拒绝原因；允许打开时返回 null
     */
    public static String reject(String url) {
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

    /**
     * IPv4 四段数字，或带冒号的地址（按 IPv6 字面量处理）。域名返回 false，避免为公网主机做 DNS。
     *
     * @param host 已小写的主机名
     * @return true 表示要做内网地址判断
     */
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
