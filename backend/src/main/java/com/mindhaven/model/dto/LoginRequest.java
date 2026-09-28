package com.mindhaven.model.dto;

import jakarta.validation.constraints.*;

import java.util.*;

public record LoginRequest(@NotBlank @Size(max = 40) String tenantSlug, @NotBlank @Size(max = 40) String username,
                           @NotBlank @Size(max = 128) String password) {
}
