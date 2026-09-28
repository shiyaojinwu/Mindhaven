package com.mindhaven.model.dto;

import jakarta.validation.constraints.*;

public record PostInput(@NotBlank @Size(max = 1000) String content, @NotBlank @Size(max = 20) String mood) {
}
