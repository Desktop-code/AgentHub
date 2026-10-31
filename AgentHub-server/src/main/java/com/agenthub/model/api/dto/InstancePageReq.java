package com.agenthub.model.api.dto;

import com.agenthub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 实例分页条件。所属域：model。providerId、capability、keyword 都可空。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class InstancePageReq extends PageQuery {

    private Long providerId;
    private String capability;
    private String keyword;
}
