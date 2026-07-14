package com.supportiq.backend.auth.dto;

import com.supportiq.backend.auth.Role;
import com.supportiq.backend.auth.User;

/** Vue publique d'un utilisateur : jamais le password_hash. */
public record UserResponse(Long id, String email, String fullName, Role role) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }
}
