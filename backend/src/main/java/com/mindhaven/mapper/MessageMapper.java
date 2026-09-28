package com.mindhaven.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindhaven.model.entity.MessageEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {
    @Insert("""
            INSERT INTO chat_message (row_id, tenant_id, owner_id, id, session_id, seq, role, content, created_at, citations_json, status, citation_check_json)
            VALUES (#{rowId}, #{tenantId}, #{ownerId}, #{id}, #{sessionId}, #{seq}, #{role}, #{content}, #{createdAt}, #{citationsJson}, #{status}, #{citationCheckJson})
            ON CONFLICT (tenant_id, owner_id, id)
            DO UPDATE SET session_id=excluded.session_id, seq=excluded.seq, role=excluded.role, content=excluded.content, citations_json=excluded.citations_json, status=excluded.status, citation_check_json=excluded.citation_check_json
            """)
    void upsert(MessageEntity row);
}
