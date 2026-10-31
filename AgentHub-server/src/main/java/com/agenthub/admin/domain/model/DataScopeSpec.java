package com.agenthub.admin.domain.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次查询要套用的数据范围。所属域：admin。
 * unrestricted 为 true 时忽略其余字段。否则各条件取并集，由 SQL 用 OR 拼接。
 */
@Data
public class DataScopeSpec {

    private boolean unrestricted;
    /** 用户列表：本人 id；应用列表：创建人 */
    private boolean includeSelf;
    private Long selfUserId;
    /** 操作者所属部门，部门树在「仅本人」时用来定位节点 */
    private Long anchorDeptId;
    private Set<Long> deptIds = new LinkedHashSet<>();
    /** 形如 0,1,3 的层级前缀，调用方保证已去掉通配符；匹配时按层级边界判定 */
    private List<String> pathPrefixes = new ArrayList<>();
}
