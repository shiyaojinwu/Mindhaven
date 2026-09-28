package com.mindhaven.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindhaven.model.entity.KnowledgeEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeMapper extends BaseMapper<KnowledgeEntity> {
    @Insert("""
            INSERT INTO knowledge_chunk (row_id, tenant_id, id, title, topic, version, source_url, text, created_at)
            VALUES (#{rowId}, #{tenantId}, #{id}, #{title}, #{topic}, #{version}, #{sourceUrl}, #{text}, #{createdAt})
            ON CONFLICT (tenant_id, id)
            DO UPDATE SET title=excluded.title, topic=excluded.topic, version=excluded.version, source_url=excluded.source_url, text=excluded.text
            """)
    void upsert(KnowledgeEntity row);
}
