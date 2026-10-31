package com.agenthub.admin.infra.persistence.mapper;

import com.agenthub.admin.domain.model.DataScopeSpec;
import com.agenthub.admin.infra.persistence.entity.SysAppEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * sys_app 映射。所属域：admin。
 */
@Mapper
public interface AppMapper extends BaseMapper<SysAppEntity> {

    /**
     * 按部门数据范围分页。密钥哈希不查出。
     *
     * @param page 分页
     * @param name 名称片段，已转义
     * @param status 状态，可空
     * @param scope 数据范围
     * @return 一页应用
     */
    IPage<SysAppEntity> selectPageByScope(Page<SysAppEntity> page,
                                           @Param("name") String name,
                                           @Param("status") Integer status,
                                           @Param("scope") DataScopeSpec scope);

    /**
     * 含已删除行。
     *
     * @param appKey 对外 appId
     * @return 行数
     */
    long countByAppKey(@Param("appKey") String appKey);
}
