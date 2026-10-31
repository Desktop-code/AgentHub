package com.agenthub.model.infra;

import com.agenthub.model.infra.adapter.ModelHttpProperties;
import com.agenthub.model.infra.crypto.AesGcmSecretStore;
import com.agenthub.model.infra.crypto.CryptoProperties;
import com.agenthub.spi.crypto.SecretStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 模型域基础设施。所属域：model。
 * 主密钥为空或不是 32 字节时，创建 SecretStore 会失败并阻止启动。
 */
@Configuration
@EnableConfigurationProperties({CryptoProperties.class, ModelHttpProperties.class})
public class ModelInfrastructure {

    /**
     * 本地 AES-GCM。注释保留在 SecretStore 上：以后可以换成 Vault 实现，本配置不要引入 Vault 客户端。
     *
     * @param properties 主密钥
     * @return 加解密组件
     */
    @Bean
    public SecretStore secretStore(CryptoProperties properties) {
        // 当前主密钥用于新写入；previous-keys 只参与解密，支撑轮换过渡期
        return new AesGcmSecretStore(properties.getSecretKey(), properties.getPreviousKeys());
    }

    /**
     * 探活专用客户端。连接超时来自配置，读超时在每次请求上设置。
     *
     * @param properties HTTP 超时
     * @return JDK HttpClient
     */
    @Bean
    public HttpClient modelHttpClient(ModelHttpProperties properties) {
        int connect = Math.max(properties.getConnectTimeoutMs(), 1);
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connect))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
