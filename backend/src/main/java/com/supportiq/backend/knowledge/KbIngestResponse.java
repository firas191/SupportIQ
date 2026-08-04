package com.supportiq.backend.knowledge;

/** Resultat de l'indexation d'un document (S5-J1). */
public record KbIngestResponse(
        String source,
        String title,
        int chunks,
        int indexed,
        int characters) {
}
