package com.agenthub.skill.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 更新 Skill 定义层元数据。所属域：skill。启停不走这个入参。
 * triggerKeywords 用 Tag 数组提交；不传（null）与传空数组等价，都会清空关键词。
 *
 * <p>真版本口径：本入参<b>不再接受</b> description / contentMd / version。
 * 简介与正文属于 skill_version，只能通过草稿接口改；版本号只能通过「发布 / 设为生效」变化。
 * 传了也不会生效（字段已移除，避免「改详情冒充升版」）。
 */
@Data
public class SkillUpdateReq {

    @NotBlank(message = "不能为空")
    @Size(max = 64, message = "最长 64")
    private String name;

    @NotBlank(message = "不能为空")
    @Size(max = 128, message = "最长 128")
    private String title;

    /** 触发关键词 Tag 数组；命中任一词则强制注入生效版正文 */
    private List<String> triggerKeywords;

    /** 备注：仅内部人员可见，永不注入模型 */
    @Size(max = 500, message = "最长 500")
    private String remark;
}
