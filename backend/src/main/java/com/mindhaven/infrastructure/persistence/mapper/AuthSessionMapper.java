package com.mindhaven.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindhaven.infrastructure.persistence.entity.AuthSessionEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuthSessionMapper extends BaseMapper<AuthSessionEntity> {}
