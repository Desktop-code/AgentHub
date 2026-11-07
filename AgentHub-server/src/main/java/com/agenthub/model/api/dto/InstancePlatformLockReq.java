package com.agenthub.model.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 设置 / 取消平台级实例入参。所属域：model。
 * <p>
 * {@code confirmRebind} 只在「该用途已有另一条平台级」时生效：未带 true 会被拒绝，
 * 前端必须在二次弹窗里明确告知「可能重建记忆向量 collection + 全量补同步」后再置 true。
 */
@Data
public class InstancePlatformLockReq {

    /** true=设为平台级；false=取消平台级（幂等） */
    @NotNull(message = "locked 不能为空")
    private Boolean locked;

    /** 已获用户二次确认的换绑标记；首次设置/取消可省略 */
    private Boolean confirmRebind;
}
