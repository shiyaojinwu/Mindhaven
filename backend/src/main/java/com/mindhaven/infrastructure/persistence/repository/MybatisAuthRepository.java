package com.mindhaven.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.mindhaven.common.error.HttpProblem;
import com.mindhaven.domain.port.AuthRepository;
import com.mindhaven.infrastructure.persistence.entity.*;
import com.mindhaven.infrastructure.persistence.mapper.*;
import com.mindhaven.security.TenantContext.Identity;
import java.sql.SQLException;
import java.util.*;
import org.springframework.dao.*;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisAuthRepository implements AuthRepository {
  private final TenantMapper tenants;
  private final UserMapper users;
  private final AuthSessionMapper sessions;

  public MybatisAuthRepository(TenantMapper tenants, UserMapper users, AuthSessionMapper sessions) {
    this.tenants = tenants;
    this.users = users;
    this.sessions = sessions;
  }

  private static boolean duplicate(DataAccessException e) {
    if (e instanceof DuplicateKeyException) return true;
    for (Throwable t = e; t != null; t = t.getCause())
      if (t instanceof SQLException sql
          && ("23505".equals(sql.getSQLState())
              || (sql.getErrorCode() == 19
                  && sql.getMessage().contains("SQLITE_CONSTRAINT_UNIQUE")))) return true;
    return false;
  }

  private Identity identity(UserEntity user, TenantEntity tenant) {
    return new Identity(
        tenant.getId(),
        tenant.getSlug(),
        tenant.getName(),
        user.getId(),
        user.getUsername(),
        user.getRole());
  }

  public void createTenant(String id, String slug, String name, String createdAt) {
    var row = new TenantEntity();
    row.setId(id);
    row.setSlug(slug);
    row.setName(name);
    row.setCreatedAt(createdAt);
    try {
      tenants.insert(row);
    } catch (DataAccessException e) {
      if (!duplicate(e)) throw e;
      throw new HttpProblem(409, "机构代码已存在，请使用其他代码或直接登录");
    }
  }

  public void createUser(
      String id, String tenant, String username, String hash, String role, String createdAt) {
    var row = new UserEntity();
    row.setId(id);
    row.setTenantId(tenant);
    row.setUsername(username);
    row.setPasswordHash(hash);
    row.setRole(role);
    row.setCreatedAt(createdAt);
    try {
      users.insert(row);
    } catch (DataAccessException e) {
      if (!duplicate(e)) throw e;
      throw new HttpProblem(409, "该机构中已存在此账号");
    }
  }

  public Optional<Account> findAccount(String slug, String username) {
    var tenant = tenants.selectOne(new QueryWrapper<TenantEntity>().eq("slug", slug));
    if (tenant == null) return Optional.empty();
    var user =
        users.selectOne(
            new QueryWrapper<UserEntity>()
                .eq("tenant_id", tenant.getId())
                .eq("username", username));
    return user == null
        ? Optional.empty()
        : Optional.of(new Account(identity(user, tenant), user.getPasswordHash()));
  }

  public void saveSession(String tokenHash, String userId, long now, long expiresAt) {
    sessions.delete(new QueryWrapper<AuthSessionEntity>().lt("expires_at", now));
    var row = new AuthSessionEntity();
    row.setTokenHash(tokenHash);
    row.setUserId(userId);
    row.setExpiresAt(expiresAt);
    sessions.insert(row);
  }

  public Optional<Identity> findSession(String tokenHash, long now) {
    var session =
        sessions.selectOne(
            new QueryWrapper<AuthSessionEntity>()
                .eq("token_hash", tokenHash)
                .gt("expires_at", now));
    if (session == null) return Optional.empty();
    var user = users.selectById(session.getUserId());
    if (user == null) return Optional.empty();
    var tenant = tenants.selectById(user.getTenantId());
    return tenant == null ? Optional.empty() : Optional.of(identity(user, tenant));
  }

  public void deleteSession(String tokenHash) {
    sessions.deleteById(tokenHash);
  }

  public List<Map<String, Object>> members(String tenant) {
    return users
        .selectList(
            new QueryWrapper<UserEntity>()
                .select("id", "username", "role", "created_at")
                .eq("tenant_id", tenant)
                .orderByAsc("created_at"))
        .stream()
        .map(
            row ->
                Map.<String, Object>of(
                    "id",
                    row.getId(),
                    "username",
                    row.getUsername(),
                    "role",
                    row.getRole(),
                    "createdAt",
                    row.getCreatedAt()))
        .toList();
  }
}
