package com.mindhaven.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindhaven.model.entity.UsageEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UsageMapper extends BaseMapper<UsageEntity> {
}
