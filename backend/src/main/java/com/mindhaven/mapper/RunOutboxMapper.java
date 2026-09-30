package com.mindhaven.mapper;

import com.mindhaven.model.chat.RunNotification;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** Internal delivery queue, never exposed through tenant-facing endpoints. */
@Mapper
public interface RunOutboxMapper {
    @Insert("INSERT INTO run_event_outbox(id,run_id,event_type,payload,terminal_payload,created_at) VALUES(#{id},#{runId},#{eventType},#{payload},#{terminalPayload},#{now})")
    void insert(@Param("id") String id, @Param("runId") String runId, @Param("eventType") String eventType,
                @Param("payload") String payload, @Param("terminalPayload") String terminalPayload, @Param("now") String now);
    @Select("SELECT id,run_id,event_type,payload,terminal_payload FROM run_event_outbox WHERE published_at IS NULL ORDER BY created_at,id LIMIT 100")
    List<RunNotification> pending();
    @Update("UPDATE run_event_outbox SET published_at=#{now} WHERE id=#{id} AND published_at IS NULL")
    void delivered(@Param("id") String id, @Param("now") String now);
}
