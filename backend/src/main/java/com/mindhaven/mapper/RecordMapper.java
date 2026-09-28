package com.mindhaven.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Composite identity includes tenant, owner, bucket and id; never use id-only CRUD here.
 */
@Mapper
public interface RecordMapper {
    @Select("SELECT payload FROM tenant_records WHERE tenant_id=#{tenant} AND owner_id=#{owner} AND" + " bucket=#{bucket} AND id=#{id}")
    String get(@Param("tenant") String tenant, @Param("owner") String owner, @Param("bucket") String bucket, @Param("id") String id);

    @Select("SELECT payload FROM tenant_records WHERE tenant_id=#{tenant} AND owner_id=#{owner} AND" + " bucket=#{bucket} ORDER BY created_at,id")
    List<String> list(@Param("tenant") String tenant, @Param("owner") String owner, @Param("bucket") String bucket);

    @Insert("INSERT INTO tenant_records(tenant_id,owner_id,bucket,id,payload,created_at)" + " VALUES(#{tenant},#{owner},#{bucket},#{id},#{payload},#{created}) ON" + " CONFLICT(tenant_id,owner_id,bucket,id) DO UPDATE SET payload=excluded.payload")
    void put(@Param("tenant") String tenant, @Param("owner") String owner, @Param("bucket") String bucket, @Param("id") String id, @Param("payload") String payload, @Param("created") String created);

    @Delete("DELETE FROM tenant_records WHERE tenant_id=#{tenant} AND owner_id=#{owner} AND" + " bucket=#{bucket} AND id=#{id}")
    void delete(@Param("tenant") String tenant, @Param("owner") String owner, @Param("bucket") String bucket, @Param("id") String id);
}
