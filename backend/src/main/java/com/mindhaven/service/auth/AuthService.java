package com.mindhaven.service.auth;

import com.mindhaven.common.Times;
import com.mindhaven.common.error.HttpProblem;
import com.mindhaven.manager.AuthManager;
import com.mindhaven.manager.KnowledgeManager;
import com.mindhaven.manager.RecordManager;
import com.mindhaven.security.LoginIdentity;
import com.mindhaven.security.LoginSession;
import com.mindhaven.security.TenantContext;
import com.mindhaven.service.questionnaire.QuestionnaireService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

@Service
public class AuthService {
    private final AuthManager authManager;
    private final RecordManager store;
    private final KnowledgeManager knowledge;
    private final QuestionnaireService questionnaires;
    private static final SecureRandom RANDOM = new SecureRandom();


    public AuthService(KnowledgeManager knowledge, AuthManager authManager, RecordManager store, QuestionnaireService questionnaires) {
        this.knowledge = knowledge;
        this.authManager = authManager;
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
            var spec = new PBEKeySpec(password.toCharArray(), Base64.getUrlDecoder().decode(salt), 210000, 256);
            try {
                return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
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
        return pieces.length == 2 && MessageDigest.isEqual(derive(password, pieces[0]).getBytes(StandardCharsets.UTF_8), pieces[1].getBytes(StandardCharsets.UTF_8));
    }

    public static String tokenHash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private LoginSession issue(LoginIdentity identity) {
        String token = random(32);
        long now = Instant.now().getEpochSecond();
        authManager.saveSession(tokenHash(token), identity.userId(), now, now + 28800);
        return new LoginSession(token, identity);
    }

    @Transactional
    public LoginSession register(String slug, String name, String username, String password) {
        String tenant = UUID.randomUUID().toString(), user = UUID.randomUUID().toString(), now = Times.now();
        authManager.createTenant(tenant, slug, name, now);
        authManager.createUser(user, tenant, username, passwordHash(password), "ADMIN", now);
        var identity = new LoginIdentity(tenant, slug, name, user, username, "ADMIN");
        try (var scope = TenantContext.open(identity)) {
            for (var k : SeedData.knowledge()) knowledge.save(k);
            for (var c : SeedData.courses()) store.put("courses", c.id(), c);
            questionnaires.seed();
        }
        return issue(identity);
    }

    public LoginSession login(String slug, String username, String password) {
        var account = authManager.findAccount(slug, username);
        if (account.isEmpty() || !matches(password, account.get().passwordHash()))
            throw new HttpProblem(401, "机构代码、账号或密码不正确");
        return issue(account.get().identity());
    }

    public Optional<LoginIdentity> authenticate(String token) {
        if (token == null || token.length() > 100) return Optional.empty();
        return authManager.findSession(tokenHash(token), Instant.now().getEpochSecond());
    }

    public void logout(String token) {
        if (token != null) authManager.deleteSession(tokenHash(token));
    }

    public List<Map<String, String>> members() {
        TenantContext.requireAdmin();
        return authManager.members(TenantContext.require().tenantId());
    }

    @Transactional
    public Map<String, String> addMember(String username, String password) {
        TenantContext.requireAdmin();
        String id = UUID.randomUUID().toString();
        authManager.createUser(id, TenantContext.require().tenantId(), username, passwordHash(password), "MEMBER", Times.now());
        return Map.of("id", id, "username", username, "role", "MEMBER");
    }
}
