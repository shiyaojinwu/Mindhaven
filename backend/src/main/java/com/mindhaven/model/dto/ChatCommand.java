package com.mindhaven.model.dto;

import jakarta.validation.constraints.*;

/**
 * Compression is controlled by the server context policy, not by request parameters.
 */
public record ChatCommand(@NotBlank @Size(max = 1500) String message, @NotBlank @Size(max = 40) String topic,
                          @NotBlank @Size(max = 40) String version, boolean rewrite, @Size(max = 80) String requestId) {
}
