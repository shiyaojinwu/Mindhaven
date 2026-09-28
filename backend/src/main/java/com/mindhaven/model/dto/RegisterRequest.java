package com.mindhaven.model.dto;

import jakarta.validation.constraints.*;

import java.util.*;

public record RegisterRequest(@NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{2,39}") String tenantSlug,
                              @NotBlank @Size(max = 120) String tenantName,
                              @NotBlank @Pattern(regexp = "[a-zA-Z0-9_.-]{3,40}") String username,
                              @NotBlank @Size(min = 10, max = 128) String password) {
}
