package com.mindhaven.application.dto;

import jakarta.validation.constraints.*;

public record ChatCommand(
    @NotBlank @Size(max = 1500) String message,
    @NotBlank @Size(max = 40) String topic,
    @NotBlank @Size(max = 40) String version,
    boolean rewrite,
    boolean compression,
    @Size(max = 80) String requestId) {}
