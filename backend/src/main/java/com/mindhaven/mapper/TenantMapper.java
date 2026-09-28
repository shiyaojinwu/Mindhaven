package com.mindhaven.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindhaven.model.entity.TenantEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TenantMapper extends BaseMapper<TenantEntity> {
}
