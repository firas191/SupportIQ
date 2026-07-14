package com.supportiq.backend.auth.dto;

import com.supportiq.backend.auth.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Creation d'un compte par un ADMIN. Le role est explicite (pas de self-service). */
public record RegisterRequest(
        @Email @NotBlank String email,
        @NotBlank @Size(min = 8, max = 100) String password,
        @NotBlank @Size(max = 255) String fullName,
        @NotNull Role role) {
}
