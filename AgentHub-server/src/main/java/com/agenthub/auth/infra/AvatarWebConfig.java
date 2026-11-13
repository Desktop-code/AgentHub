package com.agenthub.auth.infra;

import com.agenthub.auth.infra.storage.AvatarFileStorage;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 头像静态资源映射。所属域：auth。
 * <p>
 * {@code /uploads/avatars/**} 映射到落盘根目录；不在 {@code /api/**} 下，
 * 因此不走 Sa-Token 登录拦截，方便 {@code <img src>} 直接加载。
 * 文件名含 UUID，避免按 userId 枚举猜到他人头像内容。
 */
@Configuration
public class AvatarWebConfig implements WebMvcConfigurer {

    private final AvatarFileStorage storage;

    /**
     * @param storage 头像落盘
     */
    public AvatarWebConfig(AvatarFileStorage storage) {
        this.storage = storage;
    }

    /**
     * 把对外 URL 前缀挂到本地目录。
     *
     * @param registry 资源注册表
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = storage.root().toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler(AvatarFileStorage.URL_PREFIX + "**")
                .addResourceLocations(location)
                .setCachePeriod(3600);
    }
}
