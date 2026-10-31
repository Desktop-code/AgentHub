package com.agenthub.common.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 存活探针。所属域：common。
 * 约束：匿名可访问，不查库、不带业务数据。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    /**
     * @return code=0，data.status=UP
     */
    @GetMapping
    public Result<Map<String, String>> health() {
        return Result.ok(Map.of("status", "UP"));
    }
}
