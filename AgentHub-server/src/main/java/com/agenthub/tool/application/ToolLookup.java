package com.agenthub.tool.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.tool.domain.model.ToolItem;
import com.agenthub.tool.domain.model.ToolSource;
import com.agenthub.tool.domain.repository.ToolItemRepository;
import com.agenthub.tool.domain.repository.ToolSourceRepository;
import org.springframework.stereotype.Component;

/**
 * 按 id 取未删除的工具源和工具项。所属域：tool。几个应用服务共用。
 */
@Component
public class ToolLookup {

    private final ToolSourceRepository sources;
    private final ToolItemRepository items;

    /**
     * @param sources 工具源
     * @param items   工具项
     */
    public ToolLookup(ToolSourceRepository sources, ToolItemRepository items) {
        this.sources = sources;
        this.items = items;
    }

    /**
     * @param id 工具源 id
     * @return 未删除的源
     * @throws BizException 不存在时 NOT_FOUND
     */
    public ToolSource requireSource(Long id) {
        return sources.findById(id).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "工具源不存在"));
    }

    /**
     * @param id 工具项 id
     * @return 未删除的项
     * @throws BizException 不存在时 NOT_FOUND
     */
    public ToolItem requireItem(Long id) {
        return items.findById(id).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "工具项不存在"));
    }
}
