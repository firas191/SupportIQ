package com.supportiq.backend.auth.dto;

/** Identite de l'appelant courant, derivee du token (endpoint /api/auth/me). */
public record MeResponse(String email, String role) {
}
