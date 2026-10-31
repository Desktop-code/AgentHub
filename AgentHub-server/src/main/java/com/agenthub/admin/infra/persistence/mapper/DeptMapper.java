package com.agenthub.admin.infra.persistence.mapper;

import com.agenthub.admin.infra.persistence.entity.SysDeptEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * sys_dept 映射。所属域：admin。移动子树的前缀替换放在 XML。
 */
@Mapper
public interface DeptMapper extends BaseMapper<SysDeptEntity> {

    /**
     * 用 path 前缀替换下级，同时改被移动节点的 parent。不用 FIND_IN_SET。
     * 前缀为 0,1 这种逗号层级串，条件限定「等于」或「前缀+逗号开头」。
     *
     * @param id         被移动部门
     * @param parentId   新父 id，根为 0
     * @param oldPrefix  原 path
     * @param newPrefix  新 path
     * @param operatorId 操作者
     * @return 更新行数
     */
    int rewritePath(@Param("id") Long id,
                     @Param("parentId") Long parentId,
                     @Param("oldPrefix") String oldPrefix,
                     @Param("newPrefix") String newPrefix,
                     @Param("operatorId") Long operatorId);
}
