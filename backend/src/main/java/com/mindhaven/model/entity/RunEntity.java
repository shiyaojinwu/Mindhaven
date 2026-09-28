package com.mindhaven.model.entity;

import com.baomidou.mybatisplus.annotation.*;

@TableName("ai_runs")
public class RunEntity {
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

    private String sessionId;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String value) {
        this.sessionId = value;
    }

    private String requestId;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String value) {
        this.requestId = value;
    }

    private String requestHash;

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String value) {
        this.requestHash = value;
    }

    private String message;

    public String getMessage() {
        return message;
    }

    public void setMessage(String value) {
        this.message = value;
    }

    private String status;

    public String getStatus() {
        return status;
    }

    public void setStatus(String value) {
        this.status = value;
    }

    private Integer cancelRequested;

    public Integer getCancelRequested() {
        return cancelRequested;
    }

    public void setCancelRequested(Integer value) {
        this.cancelRequested = value;
    }

    private Long nextSeq;

    public Long getNextSeq() {
        return nextSeq;
    }

    public void setNextSeq(Long value) {
        this.nextSeq = value;
    }

    private String createdAt;

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String value) {
        this.createdAt = value;
    }

    private String updatedAt;

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String value) {
        this.updatedAt = value;
    }

    private String error;

    public String getError() {
        return error;
    }

    public void setError(String value) {
        this.error = value;
    }
}
