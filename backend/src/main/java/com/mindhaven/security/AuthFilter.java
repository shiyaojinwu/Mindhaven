package com.mindhaven.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindhaven.service.auth.AuthService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.*;

@Component
@Order(1)
public class AuthFilter extends OncePerRequestFilter {
    private final AuthService auth;
    private final ObjectMapper mapper;
    private static final Set<String> PUBLIC = Set.of("/api/health", "/api/auth/login", "/api/auth/register");

    public AuthFilter(AuthService auth, ObjectMapper mapper) {
        this.auth = auth;
        this.mapper = mapper;
    }

    public static String token(HttpServletRequest req) {
        if (req.getCookies() != null) for (Cookie c : req.getCookies())
            if (c.getName().equals("mindhaven_session")) return c.getValue();
        return null;
    }

    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        if (!req.getRequestURI().startsWith("/api/") || PUBLIC.contains(req.getRequestURI())) {
            chain.doFilter(req, res);
            return;
        }
        var identity = auth.authenticate(token(req));
        if (identity.isEmpty()) {
            res.setStatus(401);
            res.setContentType("application/json;charset=UTF-8");
            mapper.writeValue(res.getWriter(), Map.of("message", "请先登录"));
            return;
        }
        try (var scope = TenantContext.open(identity.get())) {
            chain.doFilter(req, res);
        }
    }
}
