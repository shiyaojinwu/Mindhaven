package com.mindhaven.mapper;

import com.mindhaven.model.knowledge.IndexState;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgeIndexMapper {
    @Select("SELECT chunk_id, fingerprint, status, updated_at FROM knowledge_index_state WHERE tenant_id=#{tenant} AND target=#{target}")
    List<IndexState> list(@Param("tenant") String tenant, @Param("target") String target);

    @Insert("""
            INSERT INTO knowledge_index_state (tenant_id, target, chunk_id, fingerprint, status, updated_at)
            VALUES (#{tenant}, #{target}, #{state.chunkId}, #{state.fingerprint}, #{state.status}, #{state.updatedAt})
            ON CONFLICT (tenant_id, target, chunk_id) DO UPDATE SET
            fingerprint=excluded.fingerprint, status=excluded.status, updated_at=excluded.updated_at
            """)
    void save(@Param("tenant") String tenant, @Param("target") String target, @Param("state") IndexState state);
}
