package com.agenthub.eval.infra;

import com.agenthub.eval.infra.evalscope.EvalScopeHttpClient;
import com.agenthub.eval.infra.openjudge.OpenJudgeHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 评测域基础设施装配。所属域：eval。
 * <p>绑定 {@link EvalProperties}；装配 OpenJudge / EvalScope HTTP 客户端
 *（独立 Bean 名，不与 MCP/模型客户端共享）。
 */
@Configuration
@EnableConfigurationProperties(EvalProperties.class)
public class EvalInfrastructure {

    /**
     * OpenJudge sidecar 专用 HttpClient；连接超时 5s，读超时由每次 Request.timeout 控制。
     *
     * @param json JSON
     * @return 客户端
     */
    @Bean
    public OpenJudgeHttpClient openJudgeHttpClient(ObjectMapper json) {
        return new OpenJudgeHttpClient(sharedHttpClient(), json);
    }

    /**
     * EvalScope sidecar 专用客户端；默认关，仅显式桥 API 可能调用。
     *
     * @param json JSON
     * @return 客户端
     */
    @Bean
    public EvalScopeHttpClient evalScopeHttpClient(ObjectMapper json) {
        return new EvalScopeHttpClient(sharedHttpClient(), json);
    }

    private static HttpClient sharedHttpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}

