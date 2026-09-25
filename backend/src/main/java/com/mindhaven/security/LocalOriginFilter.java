package com.mindhaven.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Browser calls must originate from the configured local UI. */
@org.springframework.core.annotation.Order(0)
@Component
public class LocalOriginFilter extends OncePerRequestFilter {
  private static final Set<String> ALLOWED =
      Set.of(
          "http://127.0.0.1:5173",
          "http://localhost:5173",
          "http://127.0.0.1:8080",
          "http://localhost:8080");

  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String origin = req.getHeader("Origin");
    if (req.getRequestURI().startsWith("/api/") && origin != null && !ALLOWED.contains(origin)) {
      res.sendError(403);
      return;
    }
    chain.doFilter(req, res);
  }
}
