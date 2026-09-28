package com.mindhaven.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

@TableName("ai_usage")
public class UsageEntity {
  @TableId(type = IdType.INPUT)
  private String id;

  public String getId() {
    return id;
  }

  public void setId(String value) {
    this.id = value;
  }

  private String tenantId;

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String value) {
    this.tenantId = value;
  }

  private String ownerId;

  public String getOwnerId() {
    return ownerId;
  }

  public void setOwnerId(String value) {
    this.ownerId = value;
  }

  private String runId;

  public String getRunId() {
    return runId;
  }

  public void setRunId(String value) {
    this.runId = value;
  }

  private String purpose;

  public String getPurpose() {
    return purpose;
  }

  public void setPurpose(String value) {
    this.purpose = value;
  }

  private String createdAt;

  public String getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(String value) {
    this.createdAt = value;
  }

  private String payload;

  public String getPayload() {
    return payload;
  }

  public void setPayload(String value) {
    this.payload = value;
  }
}
