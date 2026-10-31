package com.agenthub.runtime.application;

import com.agenthub.runtime.domain.service.BuiltinSchemaSource;
import com.agenthub.tool.domain.model.ToolItem;
import com.agenthub.tool.domain.model.ToolSourceType;
import com.agenthub.tool.domain.repository.ToolItemRepository;
import com.agenthub.tool.domain.repository.ToolSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

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

    /**
     * @param sources 只取 BUILTIN 源
     * @param items   按源列工具项并条件更新
     * @param schemas Java 注解生成的 schema
     */
    public BuiltinSchemaBackfill(ToolSourceRepository sources, ToolItemRepository items,
                                 BuiltinSchemaSource schemas) {
        this.sources = sources;
        this.items = items;
        this.schemas = schemas;
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
                }
            }
        }
        return filled;
    }
}
