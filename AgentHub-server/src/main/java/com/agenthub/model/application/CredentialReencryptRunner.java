package com.agenthub.model.application;

import com.agenthub.model.domain.model.ModelCredential;
import com.agenthub.model.domain.repository.ModelCredentialRepository;
import com.agenthub.spi.crypto.SecretContexts;
import com.agenthub.spi.crypto.SecretStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把模型凭证的旧密文重加密到当前主密钥与 v2 格式。所属域：model 的 application。
 * <p>触发时机：进程启动后执行一次。它是「主密钥轮换 + 老格式升级」的落地手段：
 * 配好新的 {@code agenthub.crypto.secret-key}、把旧值放进 {@code agenthub.crypto.previous-keys}，
 * 启动后本任务用新主密钥重写全部凭证密文，之后即可移除历史密钥。
 * <p>幂等：只处理 {@link SecretStore#needsUpgrade(String)} 为 true 的行。
 * 单行失败不影响其他行，也不阻止启动；日志只记 id 与异常类型，不含明文与密文。
 */
@Component
public class CredentialReencryptRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CredentialReencryptRunner.class);

    /** 单次启动最多处理的行数，防止异常数据让启动长时间阻塞。剩余部分下次启动继续。 */
    private static final int MAX_ROWS_PER_RUN = 500;

    private final ModelCredentialRepository credentials;
    private final SecretStore secretStore;

    /**
     * @param credentials 凭证仓储，按行读取密文再回写
     * @param secretStore 加解密组件，提供 needsUpgrade / decrypt / encrypt / mask
     */
    public CredentialReencryptRunner(ModelCredentialRepository credentials, SecretStore secretStore) {
        this.credentials = credentials;
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
            for (ModelCredential credential : credentials.listAll()) {
                if (handled >= MAX_ROWS_PER_RUN) {
                    log.warn("凭证密文升级达到单次上限 {}，剩余行下次启动继续", MAX_ROWS_PER_RUN);
                    break;
                }
                String cipher = credential.getApiKeyCipher();
                if (cipher == null || cipher.isBlank() || !secretStore.needsUpgrade(cipher)) {
                    continue;
                }
                handled++;
                try {
                    // AAD 与业务侧保持一致：绑定 providerId
                    String context = SecretContexts.modelCredential(credential.getProviderId());
                    String plain = secretStore.decrypt(cipher, context);
                    credentials.rewriteCipher(credential.getId(),
                            secretStore.encrypt(plain, context), secretStore.mask(plain));
                    rewritten.incrementAndGet();
                } catch (RuntimeException ex) {
                    // 连历史密钥也解不开：留给人工重置 Key，不阻断其他行
                    failed.incrementAndGet();
                    log.warn("凭证密文升级失败 credentialId={} type={}", credential.getId(), ex.getClass().getSimpleName());
                }
            }
            if (rewritten.get() > 0 || failed.get() > 0) {
                log.info("凭证密文升级完成 keyId={} rewritten={} failed={}",
                        secretStore.currentKeyId(), rewritten.get(), failed.get());
            }
        } catch (RuntimeException ex) {
            // 例如数据库暂不可用：只记异常类型，避免消息里带出 SQL 片段
            log.warn("凭证密文升级跳过 type={}", ex.getClass().getSimpleName());
        }
    }
}
