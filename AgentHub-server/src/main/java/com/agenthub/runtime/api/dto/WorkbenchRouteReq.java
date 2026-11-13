package com.agenthub.runtime.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 工作台手动换线请求。所属域：runtime（api）。
 *
 * <p>只收 {@code routeKey}：候选集合由服务端按会话快照方案解析，
 * 前端不能借此把会话指到别的方案或全平台任意 Agent。
 */
@Data
public class WorkbenchRouteReq {

    /** 目标候选的 route_key（本会话快照方案内） */
    @NotBlank(message = "请选择要切换的线路")
    private String routeKey;
}
