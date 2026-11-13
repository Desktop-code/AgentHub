package com.agenthub.agent.infra;

import com.agenthub.agent.domain.service.AgentLookPalette;
import com.agenthub.agent.infra.storage.AgentAvatarStorage;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Agent 自定义形象静态资源映射。所属域：agent。
 * <p>
 * {@code /uploads/agent-avatars/**} → 落盘根目录；不在 {@code /api/**} 下，不走登录拦截，
 * 便于 {@code <img src>} 直接加载。文件名含 UUID，不能按 userId 枚举。
 */
@Configuration
public class AgentAvatarWebConfig implements WebMvcConfigurer {

    private final AgentAvatarStorage storage;

    /**
     * @param storage Agent 形象落盘
     */
    public AgentAvatarWebConfig(AgentAvatarStorage storage) {
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
        registry.addResourceHandler(AgentLookPalette.AVATAR_URL_PREFIX + "**")
                .addResourceLocations(location)
                .setCachePeriod(3600);
    }
}
