package com.supportiq.backend.drafts;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Decision humaine sur un brouillon.
 *
 * @param status EDITED (enregistrer une correction), SENT (valider), REJECTED (ecarter)
 * @param content texte corrige ; absent = on valide le brouillon tel quel
 */
public record ReviewDraftRequest(
        @NotBlank String status,
        // Borne haute : une reponse de support ne fait pas 100 000 caracteres. Sans limite, le
        // champ est une porte ouverte a la saturation de la table.
        @Size(max = 20_000) String content) {
}
