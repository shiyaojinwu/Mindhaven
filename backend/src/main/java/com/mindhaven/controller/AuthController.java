package com.mindhaven.controller;

import com.mindhaven.model.dto.CreateMemberRequest;
import com.mindhaven.model.dto.LoginRequest;
import com.mindhaven.model.dto.RegisterRequest;
import com.mindhaven.security.AuthFilter;
import com.mindhaven.security.LoginIdentity;
import com.mindhaven.security.TenantContext;
import com.mindhaven.service.auth.AuthService;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final AuthService auth;
    private final boolean secure;

    public AuthController(AuthService auth, @Value("${mindhaven.secure-cookie:false}") boolean secure) {
        this.auth = auth;
        this.secure = secure;
    }


    private void cookie(HttpServletResponse response, String token, long seconds) {
        response.addHeader("Set-Cookie", ResponseCookie.from("mindhaven_session", token).httpOnly(true).secure(secure).sameSite("Strict").path("/api").maxAge(seconds).build().toString());
    }

    @PostMapping("/auth/register")
    public LoginIdentity register(@Valid @RequestBody RegisterRequest input, HttpServletResponse response) {
        var login = auth.register(input.tenantSlug(), input.tenantName(), input.username(), input.password());
        cookie(response, login.token(), 28800);
        return login.identity();
    }

    @PostMapping("/auth/login")
    public LoginIdentity login(@Valid @RequestBody LoginRequest input, HttpServletResponse response) {
        var login = auth.login(input.tenantSlug(), input.username(), input.password());
        cookie(response, login.token(), 28800);
        return login.identity();
    }

    @GetMapping("/auth/me")
    public LoginIdentity me() {
        return TenantContext.require();
    }

    @PostMapping("/auth/logout")
    public Map<String, Boolean> logout(HttpServletRequest request, HttpServletResponse response) {
        auth.logout(AuthFilter.token(request));
        cookie(response, "", 0);
        return Map.of("ok", true);
    }

    @GetMapping("/admin/members")
    public List<Map<String, String>> members() {
        return auth.members();
    }

    @PostMapping("/admin/members")
    public Map<String, String> addMember(@Valid @RequestBody CreateMemberRequest m) {
        return auth.addMember(m.username(), m.password());
    }
}
