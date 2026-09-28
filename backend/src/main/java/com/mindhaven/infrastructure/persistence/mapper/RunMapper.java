package com.mindhaven.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindhaven.infrastructure.persistence.entity.RunEntity;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface RunMapper extends BaseMapper<RunEntity> {
  // RETURNING must flush the MyBatis session cache and participate in the surrounding transaction.
  @Select(
      value =
          "UPDATE ai_runs SET next_seq=next_seq+1,updated_at=#{now} WHERE id=#{id} AND"
              + " tenant_id=#{tenant} AND owner_id=#{owner} AND status IN ('QUEUED','RUNNING') AND"
              + " cancel_requested=0 RETURNING next_seq",
      affectData = true)
  @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
  List<Long> appendSequence(
      @Param("id") String id,
      @Param("tenant") String tenant,
      @Param("owner") String owner,
      @Param("now") String now);

  @Select(
      value =
          "UPDATE ai_runs SET status=#{status},next_seq=next_seq+1,updated_at=#{now},error=#{error}"
              + " WHERE id=#{id} AND tenant_id=#{tenant} AND owner_id=#{owner} AND status IN"
              + " ('QUEUED','RUNNING') AND (#{status} != 'COMPLETED' OR cancel_requested=0)"
              + " RETURNING next_seq",
      affectData = true)
  @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
  List<Long> finishSequence(
      @Param("id") String id,
      @Param("tenant") String tenant,
      @Param("owner") String owner,
      @Param("status") String status,
      @Param("now") String now,
      @Param("error") String error);
}
