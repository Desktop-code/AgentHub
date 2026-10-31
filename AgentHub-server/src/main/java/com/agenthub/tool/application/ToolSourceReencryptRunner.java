package com.agenthub.tool.application;

import com.agenthub.spi.crypto.SecretContexts;
import com.agenthub.spi.crypto.SecretStore;
import com.agenthub.tool.domain.model.ToolSource;
import com.agenthub.tool.domain.repository.ToolSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把工具源鉴权头的旧密文重加密到当前主密钥与 v2 格式。所属域：tool 的 application。
 * <p>与模型凭证的迁移任务同规则：启动后执行一次、幂等、单行失败不阻断。
 * 历史密钥通过 {@code agenthub.crypto.previous-keys} 提供，迁移完成后即可移除。
 * <p>只处理有鉴权头的源；日志只记 sourceId 与异常类型，不含明文与密文。
 */
@Component
public class ToolSourceReencryptRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ToolSourceReencryptRunner.class);

    /** 单次启动最多处理的行数，防止异常数据让启动长时间阻塞。 */
    private static final int MAX_ROWS_PER_RUN = 500;

    private final ToolSourceRepository sources;
    private final SecretStore secretStore;

    /**
     * @param sources     工具源仓储
     * @param secretStore 加解密组件
     */
    public ToolSourceReencryptRunner(ToolSourceRepository sources, SecretStore secretStore) {
        this.sources = sources;
        this.secretStore = secretStore;
    }

    /**
     * 启动后执行迁移。异常一律吞掉并记日志，确保不挡进程启动。
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            AtomicInteger rewritten = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();
            int handled = 0;
            for (ToolSource source : sources.listAll()) {
                if (handled >= MAX_ROWS_PER_RUN) {
                    log.warn("工具源密文升级达到单次上限 {}，剩余行下次启动继续", MAX_ROWS_PER_RUN);
                    break;
                }
                String cipher = source.getAuthHeaderCipher();
                if (cipher == null || cipher.isBlank() || !secretStore.needsUpgrade(cipher)) {
                    continue;
                }
                handled++;
                try {
                    // AAD 与业务侧一致：绑定 sourceId
                    String context = SecretContexts.toolSource(source.getId());
                    String plain = secretStore.decrypt(cipher, context);
                    sources.rewriteCipher(source.getId(),
                            secretStore.encrypt(plain, context), secretStore.mask(plain));
                    rewritten.incrementAndGet();
                } catch (RuntimeException ex) {
                    failed.incrementAndGet();
                    log.warn("工具源密文升级失败 sourceId={} type={}", source.getId(), ex.getClass().getSimpleName());
                }
            }
            if (rewritten.get() > 0 || failed.get() > 0) {
                log.info("工具源密文升级完成 keyId={} rewritten={} failed={}",
                        secretStore.currentKeyId(), rewritten.get(), failed.get());
            }
        } catch (RuntimeException ex) {
            log.warn("工具源密文升级跳过 type={}", ex.getClass().getSimpleName());
        }
    }
}
