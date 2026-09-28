package com.mindhaven.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindhaven.domain.model.AiUsage;
import com.mindhaven.domain.port.UsageRepository;
import com.mindhaven.infrastructure.persistence.entity.UsageEntity;
import com.mindhaven.infrastructure.persistence.mapper.UsageMapper;
import com.mindhaven.security.TenantContext;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisUsageRepository implements UsageRepository {
  private final UsageMapper records;
  private final ObjectMapper json;

  public MybatisUsageRepository(UsageMapper records, ObjectMapper json) {
    this.records = records;
    this.json = json;
  }

  public void save(AiUsage u) {
    var who = TenantContext.require();
    try {
      var row = new UsageEntity();
      row.setId(u.id());
      row.setTenantId(who.tenantId());
      row.setOwnerId(who.userId());
      row.setRunId(u.runId());
      row.setPurpose(u.purpose());
      row.setCreatedAt(u.createdAt());
      row.setPayload(json.writeValueAsString(u));
      records.insert(row);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  public List<AiUsage> recent(String runId) {
    var who = TenantContext.require();
    return records
        .selectList(
            new QueryWrapper<UsageEntity>()
                .eq("tenant_id", who.tenantId())
                .eq("owner_id", who.userId())
                .eq(runId != null, "run_id", runId)
                .orderByDesc("created_at", "id")
                .last("LIMIT 200"))
        .stream()
        .map(
            row -> {
              try {
                return json.readValue(row.getPayload(), AiUsage.class);
              } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
              }
            })
        .toList();
  }
}
