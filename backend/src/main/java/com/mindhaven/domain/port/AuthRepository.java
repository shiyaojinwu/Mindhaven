package com.mindhaven.domain.port;

import com.mindhaven.security.TenantContext.Identity;
import java.util.*;

public interface AuthRepository {
  record Account(Identity identity, String passwordHash) {}

  void createTenant(String id, String slug, String name, String createdAt);

  void createUser(
      String id, String tenant, String username, String hash, String role, String createdAt);

  Optional<Account> findAccount(String slug, String username);

  void saveSession(String tokenHash, String userId, long now, long expiresAt);

  Optional<Identity> findSession(String tokenHash, long now);

  void deleteSession(String tokenHash);

  List<Map<String, Object>> members(String tenant);
}
