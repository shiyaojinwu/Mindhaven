package com.mindhaven.infrastructure.persistence;

import com.mindhaven.common.error.HttpProblem;
import com.mindhaven.domain.port.AuthRepository;
import com.mindhaven.security.TenantContext.Identity;
import java.sql.*;
import java.util.*;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuthRepository implements AuthRepository {
  private final JdbcTemplate jdbc;

  public JdbcAuthRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
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

  private static Identity identity(ResultSet rs) throws SQLException {
    return new Identity(
        rs.getString("tenant_id"),
        rs.getString("slug"),
        rs.getString("name"),
        rs.getString("id"),
        rs.getString("username"),
        rs.getString("role"));
  }

  public void createTenant(String id, String slug, String name, String createdAt) {
    try {
      jdbc.update(
          "INSERT INTO tenants(id,slug,name,created_at) VALUES(?,?,?,?)",
          id,
          slug,
          name,
          createdAt);
    } catch (DataAccessException e) {
      if (!duplicate(e)) throw e;
      throw new HttpProblem(409, "机构代码已存在，请使用其他代码或直接登录");
    }
  }

  public void createUser(
      String id, String tenant, String username, String hash, String role, String createdAt) {
    try {
      jdbc.update(
          "INSERT INTO tenant_users(id,tenant_id,username,password_hash,role,created_at)"
              + " VALUES(?,?,?,?,?,?)",
          id,
          tenant,
          username,
          hash,
          role,
          createdAt);
    } catch (DataAccessException e) {
      if (!duplicate(e)) throw e;
      throw new HttpProblem(409, "该机构中已存在此账号");
    }
  }

  public Optional<Account> findAccount(String slug, String username) {
    return jdbc
        .query(
            "SELECT u.id,u.tenant_id,u.username,u.role,u.password_hash,t.slug,t.name FROM"
                + " tenant_users u JOIN tenants t ON t.id=u.tenant_id WHERE t.slug=? AND"
                + " u.username=?",
            (rs, n) -> new Account(identity(rs), rs.getString("password_hash")),
            slug,
            username)
        .stream()
        .findFirst();
  }

  public void saveSession(String tokenHash, String userId, long now, long expiresAt) {
    jdbc.update("DELETE FROM auth_sessions WHERE expires_at<?", now);
    jdbc.update(
        "INSERT INTO auth_sessions(token_hash,user_id,expires_at) VALUES(?,?,?)",
        tokenHash,
        userId,
        expiresAt);
  }

  public Optional<Identity> findSession(String tokenHash, long now) {
    return jdbc
        .query(
            "SELECT u.id,u.tenant_id,u.username,u.role,t.slug,t.name FROM auth_sessions s JOIN"
                + " tenant_users u ON u.id=s.user_id JOIN tenants t ON t.id=u.tenant_id WHERE"
                + " s.token_hash=? AND s.expires_at>?",
            (rs, n) -> identity(rs),
            tokenHash,
            now)
        .stream()
        .findFirst();
  }

  public void deleteSession(String tokenHash) {
    jdbc.update("DELETE FROM auth_sessions WHERE token_hash=?", tokenHash);
  }

  public List<Map<String, Object>> members(String tenant) {
    return jdbc.query(
        "SELECT id,username,role,created_at FROM tenant_users WHERE tenant_id=? ORDER BY"
            + " created_at",
        (rs, n) ->
            Map.<String, Object>of(
                "id",
                rs.getString(1),
                "username",
                rs.getString(2),
                "role",
                rs.getString(3),
                "createdAt",
                rs.getString(4)),
        tenant);
  }
}
