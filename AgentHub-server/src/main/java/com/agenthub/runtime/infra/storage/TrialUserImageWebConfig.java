package com.agenthub.runtime.infra.storage;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 对话附图的静态资源映射。所属域：runtime。
 *
 * <p>{@code /uploads/trial-images/**} 不在 {@code /api/**} 下，因此不走登录拦截，
 * 用户气泡里的 {@code <img>} 才能直接出图。文件名含 UUID，不能按会话号枚举内容。
 */
@Configuration
public class TrialUserImageWebConfig implements WebMvcConfigurer {

    private final TrialUserImageStorage storage;

    /**
     * @param storage 附图落盘
     */
    public TrialUserImageWebConfig(TrialUserImageStorage storage) {
        this.storage = storage;
    }

    /**
     * 把对外前缀挂到本地目录。
     *
     * @param registry 资源注册表
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = storage.root().toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler(TrialUserImageStorage.URL_PREFIX + "**")
                .addResourceLocations(location)
                .setCachePeriod(3600);
    }
}
