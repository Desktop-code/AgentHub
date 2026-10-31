package com.agenthub.model.api.dto;

import lombok.Data;

/**
 * 连通测试结果。所属域：model。message 不含 Key。
 */
@Data
public class ConnectivityResp {

    private Boolean success;
    private Long latencyMs;
    private String message;
    private String protocol;
    private String capability;
}
