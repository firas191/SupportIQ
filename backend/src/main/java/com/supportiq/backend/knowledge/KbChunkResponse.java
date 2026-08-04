package com.supportiq.backend.knowledge;

/**
 * Fragment retrouve par la recherche (S5-J1).
 *
 * <p>{@code source}, {@code heading} et {@code chunkIndex} sont conserves parce qu'ils formeront la
 * **citation** de l'agent Resolution en S5-J3 : sans eux, un brouillon genere ne pourrait pas
 * renvoyer au passage exact qui l'a inspire.
 */
public record KbChunkResponse(
        long id,
        String title,
        String source,
        int chunkIndex,
        String heading,
        String content,
        double similarity) {
}
