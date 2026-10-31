package com.agenthub.prompt.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * L2 保存草稿（主表 + 段落同步）。所属域：prompt。
 * id 空：新建（需 templateKey）；id 为草稿：更新主信息并替换段落；
 * id 为已发布：拒绝，须不带 id 用同一 templateKey 开新版本（由服务根据 key 算 version）。
 */
@Data
public class PromptTemplateDraftReq {

    private Long id;
    private String templateKey;
    @NotBlank(message = "name 不能为空")
    private String name;
    @NotBlank(message = "purpose 不能为空")
    private String purpose;
    private String locale;
    private String protocol;
    private String remark;
    @Valid
    private List<PromptSegmentReq> segments = new ArrayList<>();
}
