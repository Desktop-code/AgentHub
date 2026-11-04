package com.agenthub.workflow.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建工作流入参。所属域：workflow。
 * <p>code 创建后冻结；name 为中文名；description 可空。
 */
@Data
public class WorkflowDefCreateReq {

    /** 租户内逻辑编码：字母/数字/点/下划线/中划线，2～64 位 */
    @NotBlank(message = "工作流编码不能为空")
    @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.-]{1,63}$",
            message = "工作流编码仅支持字母、数字、点、下划线、中划线，2～64 位")
    private String code;

    /** 中文名 */
    @NotBlank(message = "工作流名称不能为空")
    @Size(max = 128, message = "工作流名称最多 128 字")
    private String name;

    /** 说明 */
    @Size(max = 512, message = "说明最多 512 字")
    private String description;
}
