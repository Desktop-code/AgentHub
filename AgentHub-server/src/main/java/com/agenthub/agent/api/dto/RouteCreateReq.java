package com.agenthub.agent.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增一层子路由。所属域：agent。子 Agent 必须是已启用的 REACT。
 * 主路径：子 Agent + 意图；route_key / sort 可空，服务端按默认规则补全。
 */
@Data
public class RouteCreateReq {

    @NotNull(message = "不能为空")
    private Long childAgentId;

    /** 可空；空则优先用子 Agent code，冲突则 child_{id} */
    @Size(max = 64, message = "最长 64")
    private String routeKey;

    @NotBlank(message = "不能为空")
    @Size(max = 255, message = "最长 255")
    private String intent;

    /** 可空；空则同父 max(sort)+10，无子则为 10 */
    @Min(value = 0, message = "不能小于 0")
    @Max(value = 10000, message = "不能大于 10000")
    private Integer sortOrder;

    @Min(value = 0, message = "只能是 0 或 1")
    @Max(value = 1, message = "只能是 0 或 1")
    private Integer status;
}
