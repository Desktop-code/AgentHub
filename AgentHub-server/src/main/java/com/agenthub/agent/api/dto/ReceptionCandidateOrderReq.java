package com.agenthub.agent.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 默认接待候选排序请求。所属域：agent。
 *
 * <p>{@code ids} 必须是当前方案<b>全部</b>候选主键的一个排列：
 * 少传 / 多传 / 混入别的方案都会被拒绝，避免「拖了一半」把列表改成半截。
 */
@Data
public class ReceptionCandidateOrderReq {

    @NotEmpty(message = "排序列表不能为空")
    @Size(max = 16, message = "单方案候选最多 16 个")
    private List<Long> ids;
}
