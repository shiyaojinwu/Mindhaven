package com.mindhaven.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindhaven.infrastructure.persistence.entity.UsageEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UsageMapper extends BaseMapper<UsageEntity> {}
