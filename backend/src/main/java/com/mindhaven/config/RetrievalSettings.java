package com.mindhaven.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("mindhaven.retrieval")
public record RetrievalSettings(@NotNull @Pattern(regexp = "dense|hybrid") String mode,
                                @Min(4) @Max(100) int candidateLimit, @Min(1) @Max(1000) int rrfK,
                                @DecimalMin("0.0") @DecimalMax("1.0") double vectorThreshold) {
}
