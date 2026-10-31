package com.agenthub.agent.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改子路由。所属域：agent。不改父子关系，只改键、意图、排序和启停。
 * 空 route_key 保存时由服务端补生成；手改 key 须与判路由输出一致。
 * 意图空白时服务端沿用原值；原值也空则拒绝（须先补意图）。
 */
@Data
public class RouteUpdateReq {

    @Size(max = 64, message = "最长 64")
    private String routeKey;

    @Size(max = 255, message = "最长 255")
    private String intent;

    @NotNull(message = "不能为空")
    @Min(value = 0, message = "不能小于 0")
    @Max(value = 10000, message = "不能大于 10000")
    private Integer sortOrder;

    @NotNull(message = "不能为空")
    @Min(value = 0, message = "只能是 0 或 1")
    @Max(value = 1, message = "只能是 0 或 1")
    private Integer status;
}
