package com.mindhaven.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

@TableName("auth_sessions")
public class AuthSessionEntity {
  @TableId(type = IdType.INPUT)
  private String tokenHash;

  public String getTokenHash() {
    return tokenHash;
  }

  public void setTokenHash(String value) {
    this.tokenHash = value;
  }

  private String userId;

  public String getUserId() {
    return userId;
  }

  public void setUserId(String value) {
    this.userId = value;
  }

  private Long expiresAt;

  public Long getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Long value) {
    this.expiresAt = value;
  }
}
