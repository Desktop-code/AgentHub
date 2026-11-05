package com.agenthub.runtime.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 从方案新建试跑会话（虚拟根入口）。所属域：runtime。
 *
 * <p>只带方案主键：绑根方案由服务端回填 agentId，虚拟根保持 agentId 为空；
 * 客户端不能自行指定 Agent，避免把方案会话挂到不相关的 Agent 上。
 */
@Data
public class MaSessionCreateReq {

    @NotNull(message = "请选择编排方案")
    private Long maId;
}
