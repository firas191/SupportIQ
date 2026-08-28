package com.supportiq.backend.alerts;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * Une alerte detectee automatiquement (S7-J2).
 *
 * @param scope objet concerne — aujourd'hui une categorie de tickets.
 * @param bucketStart heure sur laquelle porte la mesure. Avec {@code type} et {@code scope}, elle
 *     forme l'identite de l'anomalie : c'est ce triplet qui empeche le detecteur de reproduire la
 *     meme alerte a chaque passage.
 * @param payload chiffres de la mesure (observe, attendu, score, methode). En {@link JsonNode} et
 *     non en {@code Map} : le contenu depend du type d'alerte, et le typer reviendrait a inventer
 *     une hierarchie pour trois champs.
 * @param acknowledgedAt {@code null} tant que personne n'a pris l'alerte en charge. C'est la seule
 *     partie mutable de l'objet — le reste est un constat, il ne se corrige pas.
 */
public record Alert(
        long id,
        String type,
        String severity,
        String scope,
        Instant bucketStart,
        JsonNode payload,
        Long acknowledgedBy,
        String acknowledgedByEmail,
        Instant acknowledgedAt,
        Instant createdAt) {

    public boolean acknowledged() {
        return acknowledgedAt != null;
    }
}
