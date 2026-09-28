package com.mindhaven.model.dto;

import jakarta.validation.constraints.*;

import java.util.*;

public record KnowledgeImportRequest(@NotBlank @Size(max = 120) String title, @NotBlank @Size(max = 40) String topic,
                                     @NotBlank @Size(max = 40) String version, @Size(max = 500) String sourceUrl,
                                     @NotBlank @Size(max = 700) String text) {
}
