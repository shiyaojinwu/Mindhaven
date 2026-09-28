package com.mindhaven.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindhaven.model.entity.AuthSessionEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuthSessionMapper extends BaseMapper<AuthSessionEntity> {
}
