package com.mindhaven.model.dto;

import jakarta.validation.constraints.*;

import java.util.*;

public record CreateMemberRequest(@NotBlank @Pattern(regexp = "[a-zA-Z0-9_.-]{3,40}") String username,
                                  @NotBlank @Size(min = 10, max = 128) String password) {
}
