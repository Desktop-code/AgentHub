package com.agenthub.model.domain.model;

import lombok.Data;

import java.util.List;

/**
 * 实例分页条件。所属域：model。空白筛选项表示不限制。
 * capabilities 为 IN 查询（规范码 + 旧码兼容），优先于单值 capability。
 */
@Data
public class InstanceQuery {

    private long current = 1;
    private long size = 20;
    private Long providerId;
    /** 单值用途，兼容旧调用；与 capabilities 同时有时以 capabilities 为准 */
    private String capability;
    /** 用途 IN 列表，含洗库前旧码 */
    private List<String> capabilities;
    private String keyword;
}
