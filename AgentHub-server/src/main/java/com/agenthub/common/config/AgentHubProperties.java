package com.agenthub.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * agenthub.* 配置。所属域：common。
 * 约束：安全模式默认 local；重置密码从配置读取，不写死在业务类里。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "agenthub")
public class AgentHubProperties {

    private Security security = new Security();
    private Cors cors = new Cors();

    /**
     * 安全开关与开发期初始口令。
     */
    @Data
    public static class Security {
        /** local / oauth2 / passthrough，绑定到 SecurityMode */
        private String mode = "local";
        /** 新建与重置用户时的明文密码 */
        private String resetPassword = "admin123";
    }

    /**
     * 浏览器跨域白名单。
     */
    @Data
    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>();
    }
}
