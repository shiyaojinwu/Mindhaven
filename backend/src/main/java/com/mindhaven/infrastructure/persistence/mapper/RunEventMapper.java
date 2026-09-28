package com.mindhaven.infrastructure.persistence.mapper;

import com.mindhaven.domain.model.AiRun;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface RunEventMapper {
  @Insert(
      "INSERT INTO ai_run_events(run_id,seq,name,payload) VALUES(#{id},#{seq},#{name},#{payload})")
  void insert(
      @Param("id") String id,
      @Param("seq") long seq,
      @Param("name") String name,
      @Param("payload") String payload);

  @Select(
      "SELECT seq,name,payload FROM ai_run_events WHERE run_id=#{id} AND seq>#{after} ORDER BY seq"
          + " LIMIT 200")
  List<AiRun.Event> events(@Param("id") String id, @Param("after") long after);
}
