package com.supportiq.backend.auth;

import com.supportiq.backend.auth.dto.LoginRequest;
import com.supportiq.backend.auth.dto.RegisterRequest;
import com.supportiq.backend.auth.dto.TokenResponse;
import com.supportiq.backend.auth.dto.UserResponse;
import com.supportiq.backend.common.error.EmailAlreadyUsedException;
import com.supportiq.backend.common.error.InvalidTokenException;
import com.supportiq.backend.common.security.JwtProperties;
import com.supportiq.backend.common.security.JwtService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Logique d'authentification. Les refresh tokens sont opaques (32 octets aleatoires),
 * stockes uniquement sous forme de hash SHA-256, et rotatifs : chaque usage revoque
 * l'ancien et en emet un nouveau (detection de rejeu, revocation cote serveur).
 */
@Service
public class AuthService {

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder, AuthenticationManager authenticationManager,
            JwtService jwtService, JwtProperties jwtProperties) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
    }

    /** Creation d'un compte (reservee a l'ADMIN au niveau du controleur). */
    @Transactional
    public UserResponse register(RegisterRequest req) {
        if (users.existsByEmail(req.email())) {
            throw new EmailAlreadyUsedException(req.email());
        }
        User user = User.builder()
                .email(req.email())
                .passwordHash(passwordEncoder.encode(req.password()))
                .fullName(req.fullName())
                .role(req.role())
                .build();
        return UserResponse.from(users.save(user));
    }

    /** Verifie les identifiants puis emet une paire access + refresh. */
    @Transactional
    public TokenResponse login(LoginRequest req) {
        // Leve BadCredentialsException si mauvais identifiants -> 401 via l'advice.
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.email(), req.password()));
        User user = users.findByEmail(req.email())
                .orElseThrow(() -> new InvalidTokenException("Utilisateur introuvable apres authentification"));
        return issueTokens(user);
    }

    /** Rotation : valide le refresh, revoque l'ancien, en emet un nouveau. */
    @Transactional
    public TokenResponse refresh(String rawRefreshToken) {
        RefreshToken current = refreshTokens.findByTokenHash(sha256Hex(rawRefreshToken))
                .orElseThrow(() -> new InvalidTokenException("Refresh token inconnu"));
        if (current.isRevoked() || current.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidTokenException("Refresh token expire ou revoque");
        }
        current.setRevoked(true);
        refreshTokens.save(current);
        return issueTokens(current.getUser());
    }

    /** Revocation idempotente du refresh presente (pas d'erreur si deja inconnu/revoque). */
    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokens.findByTokenHash(sha256Hex(rawRefreshToken)).ifPresent(token -> {
            token.setRevoked(true);
            refreshTokens.save(token);
        });
    }

    private TokenResponse issueTokens(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        String rawRefresh = generateRawRefreshToken();
        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(sha256Hex(rawRefresh))
                .expiresAt(Instant.now().plus(jwtProperties.refreshTtl()))
                .revoked(false)
                .build();
        refreshTokens.save(refreshToken);
        return TokenResponse.bearer(accessToken, rawRefresh, jwtService.accessTtlSeconds());
    }

    private String generateRawRefreshToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
