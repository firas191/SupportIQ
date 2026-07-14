package com.supportiq.backend.auth;

/**
 * Roles RBAC, du moins au plus privilegie : AGENT < MANAGER < ADMIN (rapport §7).
 * Stocke en base sous forme de chaine (@Enumerated(STRING)) pour la lisibilite et la stabilite.
 */
public enum Role {
    AGENT,
    MANAGER,
    ADMIN
}
