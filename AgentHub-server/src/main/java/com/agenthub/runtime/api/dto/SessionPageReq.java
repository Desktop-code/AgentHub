package com.agenthub.runtime.api.dto;

import com.agenthub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 试跑会话分页。所属域：runtime。
 *
 * <p>agentId 与 maId 二选一，且必须给一个：服务端仍按当前用户裁剪，
 * 不能靠省略用户看到别人的会话。虚拟根没有 Agent，只能按方案 id 取左栏。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SessionPageReq extends PageQuery {

    /** 绑根试跑：Agent 主键 */
    private Long agentId;

    /** 虚拟根试跑：方案主键 */
    private Long maId;
}
