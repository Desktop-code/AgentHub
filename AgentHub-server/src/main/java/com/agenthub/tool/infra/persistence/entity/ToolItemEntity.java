package com.agenthub.tool.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * tool_item。所属域：tool。列名与 sql/schema-delta-tool.sql 一致。
 * 新建默认停用，和源的 status 默认值不同。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tool_item")
public class ToolItemEntity extends ToolAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sourceId;
    private String name;
    private String title;
    private String description;
    private String inputSchemaJson;
    private Integer status;

    @TableLogic
    private Integer deleted;
}
