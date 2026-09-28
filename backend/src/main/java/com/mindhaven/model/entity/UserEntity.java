package com.mindhaven.model.entity;

import com.baomidou.mybatisplus.annotation.*;

@TableName("tenant_users")
public class UserEntity {
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

    private String username;

    public String getUsername() {
        return username;
    }

    public void setUsername(String value) {
        this.username = value;
    }

    private String passwordHash;

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String value) {
        this.passwordHash = value;
    }

    private String role;

    public String getRole() {
        return role;
    }

    public void setRole(String value) {
        this.role = value;
    }

    private String createdAt;

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String value) {
        this.createdAt = value;
    }
}
