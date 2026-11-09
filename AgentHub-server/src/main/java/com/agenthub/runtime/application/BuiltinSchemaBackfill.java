package com.agenthub.runtime.application;

import com.agenthub.runtime.domain.service.BuiltinSchemaSource;
import com.agenthub.spi.cache.AgentDictCacheInvalidator;
import com.agenthub.tool.domain.model.ToolItem;
import com.agenthub.tool.domain.model.ToolSourceType;
import com.agenthub.tool.domain.repository.ToolItemRepository;
import com.agenthub.tool.domain.repository.ToolSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动时把 BUILTIN 空白的 input_schema_json 写成 Java schema。所属域：runtime。
 * 控制台仍读库；只补空字段，不覆盖人工或同步写过的 JSON，也不动 MCP。
 * 失败只记日志，避免回填挡住进程启动（模型侧不依赖这次写库）。
 */
@Component
public class BuiltinSchemaBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BuiltinSchemaBackfill.class);

    private final ToolSourceRepository sources;
    private final ToolItemRepository items;
    private final BuiltinSchemaSource schemas;
    private final AgentDictCacheInvalidator dictCache;

    /**
     * @param sources   只取 BUILTIN 源
     * @param items     按源列工具项并条件更新
     * @param schemas   Java 注解生成的 schema
     * @param dictCache 回填后失效运行期字典（启动期无事务，立即执行）
     */
    public BuiltinSchemaBackfill(ToolSourceRepository sources, ToolItemRepository items,
                                 BuiltinSchemaSource schemas, AgentDictCacheInvalidator dictCache) {
        this.sources = sources;
        this.items = items;
        this.schemas = schemas;
        this.dictCache = dictCache;
    }

    /**
     * 容器就绪后补空。重复启动是幂等的：写上之后不再命中空白条件。
     *
     * @param args 启动参数，本回填不使用
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            int filled = fillBlank();
            log.info("BUILTIN 空 input_schema_json 回填 {} 条", filled);
        } catch (RuntimeException ex) {
            log.warn("BUILTIN schema 回填跳过 type={}", ex.getClass().getSimpleName());
        }
    }

    /**
     * @return 本次真正写上的行数
     */
    private int fillBlank() {
        int filled = 0;
        List<Long> written = new ArrayList<>();
        for (Long sourceId : sources.listIdsByType(ToolSourceType.BUILTIN.name())) {
            for (ToolItem item : items.listBySourceId(sourceId)) {
                if (item.getInputSchemaJson() != null && !item.getInputSchemaJson().isBlank()) {
                    continue;
                }
                String schema = schemas.jsonFor(item.getName()).orElse(null);
                if (schema == null) {
                    continue;
                }
                if (items.fillInputSchemaIfBlank(item.getId(), schema)) {
                    filled++;
                    written.add(item.getId());
                }
            }
        }
        // 回填改的是 schema 正文：Redis 里可能有上一次进程留下的旧值，启动完成后一并失效
        dictCache.evictToolItems(written);
        return filled;
    }
}
