-- V1 — Authentification : utilisateurs et refresh tokens rotatifs.
-- Ref. rapport §4 (modele de donnees) et §7 (securite).

CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         VARCHAR(320) NOT NULL,          -- longueur max d'une adresse email (RFC 5321)
    password_hash VARCHAR(100) NOT NULL,          -- BCrypt cost 12 -> 60 chars ; marge prevue
    full_name     VARCHAR(255) NOT NULL,
    role          VARCHAR(16)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_role  CHECK (role IN ('ADMIN', 'MANAGER', 'AGENT'))
);

CREATE TABLE refresh_tokens (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    token_hash VARCHAR(64) NOT NULL,              -- SHA-256 hex du token : jamais stocke en clair
    expires_at TIMESTAMPTZ NOT NULL,
    revoked    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_refresh_user      FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash)
);

-- Recherche des tokens actifs d'un utilisateur (revocation en cascade lors du logout / rotation).
CREATE INDEX ix_refresh_tokens_user_id ON refresh_tokens (user_id);
