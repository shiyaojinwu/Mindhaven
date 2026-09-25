package com.mindhaven.security;

import com.mindhaven.common.error.HttpProblem;

/** Identity is constructed only from the server-side session. Never from request tenant headers. */
public final class TenantContext {
  public record Identity(
      String tenantId,
      String tenantSlug,
      String tenantName,
      String userId,
      String username,
      String role) {
    public boolean admin() {
      return role.equals("ADMIN");
    }
  }

  private static final ThreadLocal<Identity> CURRENT = new ThreadLocal<>();

  private TenantContext() {}

  public static Identity require() {
    Identity i = CURRENT.get();
    if (i == null) throw new HttpProblem(401, "请先登录");
    return i;
  }

  public static void requireAdmin() {
    if (!require().admin()) throw new HttpProblem(403, "需要机构管理员权限");
  }

  public static Scope open(Identity identity) {
    Identity previous = CURRENT.get();
    CURRENT.set(identity);
    return () -> {
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
    };
  }

  public interface Scope extends AutoCloseable {
    void close();
  }
}
