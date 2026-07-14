package com.supportiq.backend.auth.dto;

/**
 * Paire de jetons renvoyee au login et au refresh.
 * expiresIn = duree de vie de l'access token en secondes (indicative pour le client).
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn) {

    public static TokenResponse bearer(String accessToken, String refreshToken, long expiresIn) {
        return new TokenResponse(accessToken, refreshToken, "Bearer", expiresIn);
    }
}
