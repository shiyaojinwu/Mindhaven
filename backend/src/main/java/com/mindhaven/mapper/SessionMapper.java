package com.mindhaven.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindhaven.model.entity.SessionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SessionMapper extends BaseMapper<SessionEntity> {
    @Insert("""
            INSERT INTO chat_session (row_id, tenant_id, owner_id, id, title, created_at)
            VALUES (#{rowId}, #{tenantId}, #{ownerId}, #{id}, #{title}, #{createdAt})
            ON CONFLICT (tenant_id, owner_id, id)
            DO UPDATE SET title=excluded.title
            """)
    void upsert(SessionEntity row);
}
