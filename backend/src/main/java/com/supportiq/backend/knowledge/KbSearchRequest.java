package com.supportiq.backend.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Interrogation de la base de connaissances (S5-J1). */
public record KbSearchRequest(
        @NotBlank @Size(min = 2, max = 1000) String question,
        Integer k) {

    /** Borne le k cote serveur : le client ne doit pas pouvoir demander 10 000 fragments. */
    public int safeK() {
        if (k == null) {
            return 5;
        }
        return Math.max(1, Math.min(k, 20));
    }
}
