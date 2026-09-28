package com.mindhaven.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

@TableName("tenants")
public class TenantEntity {
  @TableId(type = IdType.INPUT)
  private String id;

  public String getId() {
    return id;
  }

  public void setId(String value) {
    this.id = value;
  }

  private String slug;

  public String getSlug() {
    return slug;
  }

  public void setSlug(String value) {
    this.slug = value;
  }

  private String name;

  public String getName() {
    return name;
  }

  public void setName(String value) {
    this.name = value;
  }

  private String createdAt;

  public String getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(String value) {
    this.createdAt = value;
  }
}
