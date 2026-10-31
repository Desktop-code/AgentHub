package com.agenthub.tool.infra.mcp;

import java.net.http.HttpRequest;

/**
 * 把存库的整段鉴权头拆成出站头。所属域：tool 的 infra。
 * 含冒号时按第一个冒号分成头名和值，否则当作 Authorization。
 */
final class AuthHeaders {

    private AuthHeaders() {
    }

    /**
     * @param builder 出站请求
     * @param raw     已解密的整段头，空白则不加
     */
    static void apply(HttpRequest.Builder builder, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        int colon = raw.indexOf(':');
        String name;
        String value;
        if (colon > 0) {
            name = raw.substring(0, colon).trim();
            value = raw.substring(colon + 1).trim();
        } else {
            name = "Authorization";
            value = raw.trim();
        }
        if (name.isEmpty() || value.isEmpty()) {
            throw new McpCallException("鉴权头不完整");
        }
        try {
            builder.header(name, value);
        } catch (IllegalArgumentException ex) {
            throw new McpCallException("鉴权头名称不可用");
        }
    }
}
