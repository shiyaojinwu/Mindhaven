package com.mindhaven.application.auth;

import com.mindhaven.application.questionnaire.QuestionnaireService;
import com.mindhaven.common.Times;
import com.mindhaven.common.error.HttpProblem;
import com.mindhaven.domain.port.AuthRepository;
import com.mindhaven.domain.port.RecordStore;
import com.mindhaven.security.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private final AuthRepository repository;
  private final RecordStore store;
  private final QuestionnaireService questionnaires;
  private static final SecureRandom RANDOM = new SecureRandom();

  public record Login(String token, TenantContext.Identity identity) {}

  public AuthService(
      AuthRepository repository, RecordStore store, QuestionnaireService questionnaires) {
    this.repository = repository;
    this.store = store;
    this.questionnaires = questionnaires;
  }

  private static String random(int size) {
    byte[] b = new byte[size];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private static String derive(String password, String salt) {
    try {
      var spec =
          new PBEKeySpec(password.toCharArray(), Base64.getUrlDecoder().decode(salt), 210000, 256);
      try {
        return Base64.getEncoder()
            .encodeToString(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded());
      } finally {
        spec.clearPassword();
      }
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String passwordHash(String password) {
    String salt = random(16);
    return salt + ":" + derive(password, salt);
  }

  private static boolean matches(String password, String hash) {
    String[] pieces = hash.split(":", 2);
    return pieces.length == 2
        && MessageDigest.isEqual(
            derive(password, pieces[0]).getBytes(StandardCharsets.UTF_8),
            pieces[1].getBytes(StandardCharsets.UTF_8));
  }

  public static String tokenHash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Login issue(TenantContext.Identity identity) {
    String token = random(32);
    long now = Instant.now().getEpochSecond();
    repository.saveSession(tokenHash(token), identity.userId(), now, now + 28800);
    return new Login(token, identity);
  }

  @Transactional
  public Login register(String slug, String name, String username, String password) {
    String tenant = UUID.randomUUID().toString(),
        user = UUID.randomUUID().toString(),
        now = Times.now();
    repository.createTenant(tenant, slug, name, now);
    repository.createUser(user, tenant, username, passwordHash(password), "ADMIN", now);
    var identity = new TenantContext.Identity(tenant, slug, name, user, username, "ADMIN");
    try (var scope = TenantContext.open(identity)) {
      for (var k : SeedData.knowledge()) store.put("knowledge", k.id(), k);
      for (var c : SeedData.courses()) store.put("courses", c.id(), c);
      questionnaires.seed();
    }
    return issue(identity);
  }

  public Login login(String slug, String username, String password) {
    var account = repository.findAccount(slug, username);
    if (account.isEmpty() || !matches(password, account.get().passwordHash()))
      throw new HttpProblem(401, "机构代码、账号或密码不正确");
    return issue(account.get().identity());
  }

  public Optional<TenantContext.Identity> authenticate(String token) {
    if (token == null || token.length() > 100) return Optional.empty();
    return repository.findSession(tokenHash(token), Instant.now().getEpochSecond());
  }

  public void logout(String token) {
    if (token != null) repository.deleteSession(tokenHash(token));
  }

  public List<Map<String, Object>> members() {
    TenantContext.requireAdmin();
    return repository.members(TenantContext.require().tenantId());
  }

  @Transactional
  public Map<String, Object> addMember(String username, String password) {
    TenantContext.requireAdmin();
    String id = UUID.randomUUID().toString();
    repository.createUser(
        id,
        TenantContext.require().tenantId(),
        username,
        passwordHash(password),
        "MEMBER",
        Times.now());
    return Map.of("id", id, "username", username, "role", "MEMBER");
  }
}
