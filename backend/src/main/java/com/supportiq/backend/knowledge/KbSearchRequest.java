package com.supportiq.backend.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Interrogation de la base de connaissances (S5-J1, mode ajoute en S5-J2).
 *
 * <p>{@code mode} vaut {@code hybrid} (defaut : BM25 + vecteurs, fusion RRF, reclassement) ou
 * {@code vector} (embeddings seuls — le comportement du J1, conserve comme point de comparaison).
 * Toute autre valeur est ramenee au defaut plutot que rejetee : ce parametre pilote une strategie
 * interne, pas une donnee metier, et un client qui se trompe merite une reponse, pas un 400.
 */
public record KbSearchRequest(
        @NotBlank @Size(min = 2, max = 1000) String question,
        Integer k,
        String mode) {

    /** Borne le k cote serveur : le client ne doit pas pouvoir demander 10 000 fragments. */
    public int safeK() {
        if (k == null) {
            return 5;
        }
        return Math.max(1, Math.min(k, 20));
    }

    /** Mode normalise ; toute valeur inconnue retombe sur l'hybride. */
    public String safeMode() {
        return "vector".equalsIgnoreCase(mode) ? "vector" : "hybrid";
    }
}
