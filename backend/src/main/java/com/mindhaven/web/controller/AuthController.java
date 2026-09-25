package com.mindhaven.web.controller;

import com.mindhaven.application.auth.AuthService;
import com.mindhaven.security.AuthFilter;
import com.mindhaven.security.TenantContext;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthController {
  private final AuthService auth;
  private final boolean secure;

  public AuthController(
      AuthService auth, @Value("${mindhaven.secure-cookie:false}") boolean secure) {
    this.auth = auth;
    this.secure = secure;
  }

  public record Register(
      @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{2,39}") String tenantSlug,
      @NotBlank @Size(max = 120) String tenantName,
      @NotBlank @Pattern(regexp = "[a-zA-Z0-9_.-]{3,40}") String username,
      @NotBlank @Size(min = 10, max = 128) String password) {}

  public record Credentials(
      @NotBlank @Size(max = 40) String tenantSlug,
      @NotBlank @Size(max = 40) String username,
      @NotBlank @Size(max = 128) String password) {}

  public record Member(
      @NotBlank @Pattern(regexp = "[a-zA-Z0-9_.-]{3,40}") String username,
      @NotBlank @Size(min = 10, max = 128) String password) {}

  private void cookie(HttpServletResponse response, String token, long seconds) {
    response.addHeader(
        "Set-Cookie",
        ResponseCookie.from("mindhaven_session", token)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Strict")
            .path("/api")
            .maxAge(seconds)
            .build()
            .toString());
  }

  @PostMapping("/auth/register")
  public Object register(@Valid @RequestBody Register input, HttpServletResponse response) {
    var login =
        auth.register(input.tenantSlug(), input.tenantName(), input.username(), input.password());
    cookie(response, login.token(), 28800);
    return login.identity();
  }

  @PostMapping("/auth/login")
  public Object login(@Valid @RequestBody Credentials input, HttpServletResponse response) {
    var login = auth.login(input.tenantSlug(), input.username(), input.password());
    cookie(response, login.token(), 28800);
    return login.identity();
  }

  @GetMapping("/auth/me")
  public Object me() {
    return TenantContext.require();
  }

  @PostMapping("/auth/logout")
  public Object logout(HttpServletRequest request, HttpServletResponse response) {
    auth.logout(AuthFilter.token(request));
    cookie(response, "", 0);
    return Map.of("ok", true);
  }

  @GetMapping("/admin/members")
  public Object members() {
    return auth.members();
  }

  @PostMapping("/admin/members")
  public Object addMember(@Valid @RequestBody Member m) {
    return auth.addMember(m.username(), m.password());
  }
}
