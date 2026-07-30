package com.supportiq.backend.tickets;

import jakarta.validation.constraints.NotBlank;

/**
 * Correction humaine d'un champ d'analyse (S4-J4). `field` et `value` sont valides contre des
 * listes fermees cote service (les deux alimentent du SQL et de la donnee d'entrainement).
 */
public record AnnotationRequest(
        @NotBlank String field,   // priority | category | sentiment
        @NotBlank String value) {
}
