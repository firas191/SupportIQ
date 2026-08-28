package com.supportiq.backend.topics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Un sujet emergent d'un instantane (S7-J1).
 *
 * @param growth croissance entre les deux moities de la fenetre, en pourcentage.
 *     <b>{@code null} n'est pas zero</b> : il signifie que le sujet est apparu pendant la fenetre,
 *     et qu'il n'y a donc rien a quoi le comparer. L'interface en tire « nouveau », qui dit plus
 *     qu'un pourcentage n'aurait su le faire.
 * @param sampleTicketIds tickets les plus <i>centraux</i> du groupe — ceux qui justifient le
 *     libelle. Ils sont la pour qu'un responsable puisse verifier d'un clic, plutot que croire.
 * @param topCategory categorie majoritaire, ou {@code null} si aucune ne l'emporte. Un groupe
 *     partage entre trois categories n'en a pas, et en afficher une donnerait une fausse certitude.
 */
public record Topic(
        long id,
        Instant computedAt,
        int windowDays,
        String label,
        int size,
        int recentCount,
        int previousCount,
        BigDecimal growth,
        List<Long> sampleTicketIds,
        String topCategory) {
}
