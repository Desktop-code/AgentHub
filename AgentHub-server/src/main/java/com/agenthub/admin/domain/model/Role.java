package com.agenthub.admin.domain.model;

import com.agenthub.admin.domain.enums.DataScope;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 平台角色。所属域：admin。customDeptIds 仅在数据范围为自定义时有意义。
 */
@Data
public class Role {

    private Long id;
    private String code;
    private String name;
    private DataScope dataScope;
    private Integer sort;
    private Integer status;
    private String remark;
    private LocalDateTime createTime;
    private List<Long> menuIds = new ArrayList<>();
    private List<Long> customDeptIds = new ArrayList<>();
}
