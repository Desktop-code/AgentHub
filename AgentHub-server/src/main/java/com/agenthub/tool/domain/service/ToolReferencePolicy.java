package com.agenthub.tool.domain.service;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.tool.domain.model.ToolSource;
import com.agenthub.tool.domain.model.ToolSourceType;
import com.agenthub.tool.domain.model.ToolTransport;
import org.springframework.stereotype.Component;

/**
 * 工具源与工具项的引用规则。所属域：tool。
 * 库里不建物理外键，删除、同步和重名都在这里拒绝。
 */
@Component
public class ToolReferencePolicy {

    /**
     * 内置源只能看，不能改连接、启停或重置鉴权头。
     *
     * @param source 已加载的源
     */
    public void assertMutable(ToolSource source) {
        if (isBuiltin(source)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "内置工具源只读");
        }
    }

    /**
     * 内置源直接拒绝，避免种子卡片被逻辑删掉。
     *
     * @param source 已加载的源
     */
    public void assertDeletable(ToolSource source) {
        if (isBuiltin(source)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能删除内置工具源");
        }
    }

    /**
     * 只有启用中的 MCP 源（SSE 或 Streamable HTTP）可以拉 tools/list。
     *
     * @param source 已加载的源
     */
    public void assertSyncable(ToolSource source) {
        if (source == null || !ToolSourceType.MCP.name().equals(source.getType())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只有 MCP 工具源可以同步");
        }
        // 存量空 transport 按 SSE；显式 HTTP 同等可同步
        String transport = source.getTransport() == null || source.getTransport().isBlank()
                ? ToolTransport.SSE.name()
                : source.getTransport();
        if (!ToolTransport.SSE.name().equals(transport) && !ToolTransport.HTTP.name().equals(transport)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "传输仅支持 SSE 或 HTTP");
        }
        if (source.getStatus() == null || source.getStatus() != 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "工具源已停用，不能同步");
        }
        if (source.getBaseUrl() == null || source.getBaseUrl().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少 MCP 地址");
        }
    }

    /**
     * 同一源下未删除的调用名只能有一条，同步 upsert 依赖这一点。
     *
     * @param nameTaken 未删除范围内是否已有同名项
     */
    public void assertNameAvailable(boolean nameTaken) {
        if (nameTaken) {
            throw new BizException(ErrorCode.DUPLICATE, "同工具源下已有同名工具项");
        }
    }

    private boolean isBuiltin(ToolSource source) {
        return source != null && ToolSourceType.BUILTIN.name().equals(source.getType());
    }
}
