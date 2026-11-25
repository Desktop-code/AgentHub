package com.agenthub.prompt;

import com.agenthub.agent.domain.service.AgentPromptProperties;
import com.agenthub.agent.domain.service.PlatformConstraintSource;
import com.agenthub.agent.domain.service.PlatformPromptPrefix;
import com.agenthub.prompt.application.PromptAssembler;
import com.agenthub.prompt.application.PromptContextAdapters;
import com.agenthub.prompt.domain.model.PromptConstraintSnapshot;
import com.agenthub.prompt.domain.model.PromptSegmentSnapshot;
import com.agenthub.prompt.domain.model.PromptTemplateRef;
import com.agenthub.prompt.domain.model.PromptTemplateSnapshot;
import com.agenthub.prompt.domain.repository.PromptSnapshotProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 提示词相关单测的公共夹子。所属域：prompt（test）。
 * <p>
 * 为什么需要它：L1 的正源已从 classpath 文件改为库表，单测若各自 mock 会跑出多套口径。
 * 本类提供**内存快照**（不连库、不连 Redis、不出站模型），正文取自现有种子资源，
 * 保证断言关键词与 `sql/data-init-prompt-l1-seed.sql` 一致。
 * <p>
 * 线程安全：返回的实例均为测试内新建，不共享可变状态。
 */
public final class PromptTestFixtures {

    private static final String L1_PATH = "prompt/platform-prefix-zh.txt";
    private static final String ROUTER_PATH = "prompt/platform-prefix-router-zh.txt";

    private PromptTestFixtures() {
    }

    /**
     * @return classpath 中的 L1 种子正文（与 L1 种子 SQL 对齐）
     */
    public static String seedConstraintText() {
        return read(L1_PATH);
    }

    /**
     * @return classpath 中的 ROUTER 追加句
     */
    public static String seedRouterExtra() {
        return read(ROUTER_PATH);
    }

    /**
     * @return 以种子文件为正文的 L1 来源；用于测试替代库表
     */
    public static PlatformConstraintSource seedSource() {
        return locale -> Optional.of(seedConstraintText());
    }

    /**
     * @return 空来源：模拟「L1 从未发布」，用于验证阻断
     */
    public static PlatformConstraintSource emptySource() {
        return locale -> Optional.empty();
    }

    /**
     * @return 启用的平台前缀（源为种子文件）
     */
    public static PlatformPromptPrefix prefix() {
        return new PlatformPromptPrefix(new AgentPromptProperties(), seedSource(), seedRouterExtra());
    }

    /**
     * @return 关闭的平台前缀，用于验证「关闭后不再强制前缀」
     */
    public static PlatformPromptPrefix disabledPrefix() {
        AgentPromptProperties properties = new AgentPromptProperties();
        properties.setPromptPrefixEnabled(false);
        return new PlatformPromptPrefix(properties, seedSource(), seedRouterExtra());
    }

    /**
     * @return 内存快照：L1 为种子正文；L2 含 CHAT / ROUTER / REWRITE / SUMMARY / MEMORY 五个用途
     */
    public static InMemorySnapshots snapshots() {
        return new InMemorySnapshots();
    }

    /**
     * @param snapshots 快照来源
     * @return 生产同构的组装门面（L1 启用、zh-CN、24k 预算）
     */
    public static PromptAssembler assembler(PromptSnapshotProvider snapshots) {
        return new PromptAssembler(snapshots, new ObjectMapper(), true, "zh-CN", 24_000);
    }

    /**
     * @return 与生产同构的 Context 适配器
     */
    public static PromptContextAdapters adapters() {
        return new PromptContextAdapters();
    }

    /**
     * @return 各用途的模板键，与左栏固定槽位口径一致（键名 = 用途名）
     */
    public static String templateKeyOf(String purpose) {
        return purpose;
    }

    private static String read(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n").replace('\r', '\n').trim();
        } catch (IOException ex) {
            throw new IllegalStateException("测试资源缺失：" + path, ex);
        }
    }

    /**
     * 内存快照实现。模板结构与 `sql/data-init-prompt-l2-purpose-seed.sql` 同形：
     * 每个用途一个 core 锁定段 + 一个 soft 段。
     */
    public static final class InMemorySnapshots implements PromptSnapshotProvider {

        private final Map<String, PromptTemplateSnapshot> byKey = new LinkedHashMap<>();
        /** 用途 → 当前生效引用；发布切换就是改这张表 */
        private final Map<String, PromptTemplateRef> activeByPurpose = new LinkedHashMap<>();
        private String constraint = seedConstraintText();
        private int constraintVersion = 1;

        private InMemorySnapshots() {
            register(1L, "CHAT", "core_contract", "输出契约", "CORE 输出契约：先直接回答，再补充解释。",
                    "role_tone", "角色与语气", "SOFT 默认语气：专业、简洁。");
            register(2L, "ROUTER", "core_contract", "路由契约",
                    "CORE 路由契约：只做意图判定，输出 JSON。",
                    "route_style", "判定口径", "SOFT 判定口径：看真实诉求。");
            register(3L, "REWRITE", "core_contract", "改写契约",
                    "CORE 改写契约：只输出改写后的查询。",
                    "rewrite_style", "改写风格", "SOFT 改写风格：压缩成检索短句。");
            register(4L, "SUMMARY", "core_contract", "摘要契约",
                    "CORE 摘要契约：只依据输入生成摘要。",
                    "summary_style", "摘要风格", "SOFT 摘要风格：3 到 6 条要点。");
            register(5L, "MEMORY", "core_contract", "抽取契约",
                    "CORE 抽取契约：只抽可长期复用的事实。",
                    "memory_style", "输出格式", "SOFT 输出格式：JSON 数组。");
        }

        /**
         * 注册一个用途模板并设为生效。
         */
        private void register(Long id, String key, String coreKey, String coreName, String coreText,
                              String softKey, String softName, String softText) {
            PromptTemplateSnapshot template = new PromptTemplateSnapshot();
            template.setId(id);
            template.setTemplateKey(key);
            template.setPurpose(key);
            template.setLocale("zh-CN");
            template.setVersion(1);
            List<PromptSegmentSnapshot> segs = new ArrayList<>();
            segs.add(new PromptSegmentSnapshot(coreKey, coreName, 10, 0, 1, coreText));
            segs.add(new PromptSegmentSnapshot(softKey, softName, 20, 1, 0, softText));
            template.setSegments(segs);
            byKey.put(key + "@1", template);
            activeByPurpose.put(key, refOf(template));
        }

        /**
         * 模拟「某用途未配置」：移除生效引用（模板行仍留着，验证不是靠空模板顶替）。
         *
         * @param purpose 用途
         */
        public void unpublishPurpose(String purpose) {
            activeByPurpose.remove(purpose);
        }

        /**
         * 模拟 L1 从未发布。
         */
        public void withoutConstraint() {
            this.constraint = null;
        }

        /**
         * 模拟 L1 换版（发布新版本后生效）。
         *
         * @param text    新正文
         * @param version 新版本号
         */
        public void publishConstraint(String text, int version) {
            this.constraint = text;
            this.constraintVersion = version;
        }

        /**
         * 模拟平台发新版模板：新增 v2 并把该用途生效引用切到 v2。
         *
         * @param key     业务键
         * @param coreText v2 的 core 正文
         */
        public void publishNewVersion(String key, String coreText) {
            PromptTemplateSnapshot v1 = byKey.get(key + "@1");
            PromptTemplateSnapshot v2 = new PromptTemplateSnapshot();
            v2.setId(v1.getId() + 100);
            v2.setTemplateKey(key);
            v2.setPurpose(key);
            v2.setLocale("zh-CN");
            v2.setVersion(2);
            List<PromptSegmentSnapshot> segs = new ArrayList<>();
            segs.add(new PromptSegmentSnapshot("core_contract", "输出契约", 10, 0, 1, coreText));
            segs.add(new PromptSegmentSnapshot("role_tone", "角色与语气", 20, 1, 0, "SOFT v2 语气"));
            v2.setSegments(segs);
            byKey.put(key + "@2", v2);
            activeByPurpose.put(key, refOf(v2));
        }

        @Override
        public Optional<PromptConstraintSnapshot> activeConstraint(String locale) {
            if (constraint == null) {
                return Optional.empty();
            }
            return Optional.of(new PromptConstraintSnapshot(locale, constraintVersion, constraint));
        }

        @Override
        public Optional<PromptTemplateSnapshot> templateByKey(String templateKey, String locale, int version) {
            return Optional.ofNullable(byKey.get(templateKey + "@" + version));
        }

        @Override
        public Optional<PromptTemplateSnapshot> templateById(Long id) {
            return byKey.values().stream().filter(item -> item.getId().equals(id)).findFirst();
        }

        @Override
        public Optional<PromptTemplateRef> activeRefByPurpose(String purpose, String locale) {
            return Optional.ofNullable(activeByPurpose.get(purpose));
        }

        @Override
        public Optional<PromptTemplateRef> activeRefByKey(String templateKey, String locale) {
            return Optional.ofNullable(activeByPurpose.get(templateKey));
        }

        private static PromptTemplateRef refOf(PromptTemplateSnapshot template) {
            return new PromptTemplateRef(template.getId(), template.getTemplateKey(), template.getPurpose(),
                    template.getLocale(), template.getVersion());
        }
    }
}
