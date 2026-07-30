package com.supportiq.backend.tickets;

import jakarta.validation.constraints.NotNull;

/** Fusion de doublons : le ticket de l'URL est marque comme doublon de {@code targetId}. */
public record MergeRequest(@NotNull Long targetId) {
}
