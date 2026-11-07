package com.agenthub.runtime.infra.agentscope;

import com.agenthub.memory.domain.service.MemoryModelClient;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.model.ModelProvider;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.model.domain.repository.ModelProviderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * 记忆/摘要类出站的「目标描述」，只供日志使用。所属域：runtime 的 infra。
 *
 * <p>为什么单独一层：{@link MemoryModelClient} 的实现只拿到实例 id，
 * 而排障需要区分「哪个实例、什么用途、打到哪个地址」。这里把实例与供应商读出来，
 * 拼成一条可打日志的摘要。
 *
 * <p><b>脱敏红线</b>：
 * <ul>
 *   <li>绝不返回 apiKey / Authorization / 密文；</li>
 *   <li>地址只保留 host + path，<b>丢掉</b> scheme、userinfo（{@code user:pass@}）与 query
 *       （部分厂商把签名或 {@code ?key=} 放在 query 里）；</li>
 *   <li>解析失败时退化为「去掉 query 后再去 userinfo」的字符串裁剪，仍不打印原文尾部。</li>
 * </ul>
 *
 * <p>失败策略：查询异常只让描述降级为空，绝不影响真正的模型出站。
 */
@Component
public class ModelCallDescriber {

    private static final Logger log = LoggerFactory.getLogger(ModelCallDescriber.class);

    private final ModelInstanceRepository instances;
    private final ModelProviderRepository providers;

    /**
     * @param instances 模型实例
     * @param providers 供应商（协议与 baseUrl）
     */
    public ModelCallDescriber(ModelInstanceRepository instances, ModelProviderRepository providers) {
        this.instances = instances;
        this.providers = providers;
    }

    /**
     * 读取实例与供应商，产出日志用的目标描述。
     *
     * @param instanceId 模型实例 id，可空
     * @return 描述；实例缺失或查询异常时字段尽量降级，不为 null
     */
    public MemoryModelClient.Outbound describe(Long instanceId) {
        if (instanceId == null) {
            return MemoryModelClient.Outbound.unknown(null);
        }
        try {
            ModelInstance instance = instances.findById(instanceId).orElse(null);
            if (instance == null) {
                return MemoryModelClient.Outbound.unknown(instanceId);
            }
            ModelProvider provider = instance.getProviderId() == null
                    ? null
                    : providers.findById(instance.getProviderId()).orElse(null);
            return new MemoryModelClient.Outbound(
                    instanceId,
                    instance.getDisplayName(),
                    instance.getModelId(),
                    instance.getCapability(),
                    provider == null ? null : provider.getProtocol(),
                    provider == null ? "" : endpointOf(provider.getBaseUrl()));
        } catch (RuntimeException ex) {
            // 描述只服务日志：查库失败不能把记忆抽取本身拦下来
            log.debug("读取出站模型描述失败 instanceId={} type={}", instanceId, ex.getClass().getSimpleName());
            return MemoryModelClient.Outbound.unknown(instanceId);
        }
    }

    /**
     * 地址脱敏：只留 host + path。
     *
     * <p>例：{@code https://user:pw@api.example.com/v1?key=sk-x} → {@code api.example.com/v1}
     *
     * @param baseUrl 供应商配置的地址，可空
     * @return 脱敏后的 host+path；空输入返回空串
     */
    public static String endpointOf(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        String raw = baseUrl.trim();
        try {
            URI uri = URI.create(raw);
            String host = uri.getHost();
            if (host != null && !host.isBlank()) {
                String path = uri.getPath() == null ? "" : uri.getPath();
                return trimSlash(host + path);
            }
            // 相对形式（如 api.example.com/v1）解析不出 host：继续走下面的字符串裁剪
        } catch (IllegalArgumentException ignored) {
            // 非法 URI 也要能打日志：降级到字符串裁剪
        }
        String withoutQuery = cutAt(raw, '?');
        withoutQuery = cutAt(withoutQuery, '#');
        int scheme = withoutQuery.indexOf("://");
        if (scheme >= 0) {
            withoutQuery = withoutQuery.substring(scheme + 3);
        }
        int at = withoutQuery.indexOf('@');
        if (at >= 0) {
            withoutQuery = withoutQuery.substring(at + 1);
        }
        return trimSlash(withoutQuery);
    }

    private static String cutAt(String text, char mark) {
        int index = text.indexOf(mark);
        return index < 0 ? text : text.substring(0, index);
    }

    private static String trimSlash(String text) {
        String value = text.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
