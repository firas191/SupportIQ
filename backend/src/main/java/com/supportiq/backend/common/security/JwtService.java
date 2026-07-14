package com.supportiq.backend.common.security;

import com.supportiq.backend.auth.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Emission et verification des access tokens (HS256, jjwt).
 * Les refresh tokens ne sont PAS des JWT : ce sont des jetons opaques aleatoires
 * geres par AuthService (stockes hashes, rotatifs, revocables).
 */
@Service
public class JwtService {

    private final JwtProperties props;
    private final SecretKey key;

    public JwtService(JwtProperties props) {
        this.props = props;
        if (props.secret() == null
                || props.secret().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "app.security.jwt.secret doit faire au moins 32 octets (256 bits) pour HS256. "
                            + "Definir la variable d'environnement JWT_SECRET.");
        }
        this.key = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(props.issuer())
                .subject(user.getEmail())
                .claim("role", user.getRole().name())
                .claim("uid", user.getId())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(props.accessTtl())))
                .signWith(key)
                .compact();
    }

    /** Verifie signature + expiration ; leve JwtException si invalide. */
    public Jws<Claims> parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
    }

    public long accessTtlSeconds() {
        return props.accessTtl().toSeconds();
    }
}
