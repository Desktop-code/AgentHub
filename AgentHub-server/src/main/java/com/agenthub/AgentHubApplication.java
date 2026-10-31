package com.agenthub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

/**
 * AgentHub 基座启动入口。
 * <p>职责：拉起 standalone 控制台服务。所属域：启动层。
 * 约束：不扫描、不装配 Agent 运行时；时区固定为上海，和 JDBC、JSON 一致。
 */
@SpringBootApplication
public class AgentHubApplication {

    /**
     * 启动前把 JVM 默认时区钉在上海，避免登录时间比数据库少 8 小时。
     *
     * @param args 标准启动参数
     */
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        SpringApplication.run(AgentHubApplication.class, args);
    }
}
