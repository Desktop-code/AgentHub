package com.agenthub.runtime.api.dto;

import com.agenthub.common.api.PageQuery;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 试跑会话分页。所属域：runtime。
 * agentId 必填。服务端仍按当前用户裁剪，不能靠省略用户看到别人的会话。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SessionPageReq extends PageQuery {

    @NotNull(message = "不能为空")
    private Long agentId;
}
