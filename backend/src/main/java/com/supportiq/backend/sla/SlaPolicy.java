package com.supportiq.backend.sla;

import java.time.Duration;
import java.time.Instant;

/**
 * Politique d'echeance SLA (S7-J3).
 *
 * <p>Trois valeurs, volontairement lisibles : HIGH 4 h, MEDIUM 24 h, LOW 72 h. Une priorite
 * inconnue est traitee comme du courant (24 h) et non comme une urgence — traiter l'inconnu comme
 * urgent ferait basculer les 10 000 tickets non analyses en rouge le jour du deploiement, ce qui
 * revient a n'avoir plus aucune urgence.
 *
 * <p><b>Cette regle est ecrite a trois endroits</b> : ici, dans la migration V17 (backfill de
 * l'existant) et dans {@code app/sla/features.py} (le modele a besoin du budget pour calculer la
 * part consommee). C'est une duplication assumee : la migration doit rattraper l'existant sans
 * dependre de l'application, l'application doit dater les tickets a venir sans repasser par une
 * migration, et le service IA doit pouvoir scorer sans appeler Spring. Une abstraction partagee
 * entre SQL, Java et Python couterait bien plus que ces trois lignes.
 */
public final class SlaPolicy {

    private static final Duration HIGH = Duration.ofHours(4);
    private static final Duration MEDIUM = Duration.ofHours(24);
    private static final Duration LOW = Duration.ofHours(72);

    private SlaPolicy() {
    }

    public static Duration budget(String priority) {
        if (priority == null) {
            return MEDIUM;
        }
        return switch (priority) {
            case "HIGH" -> HIGH;
            case "LOW" -> LOW;
            default -> MEDIUM;
        };
    }

    /** Echeance d'un ticket cree a {@code createdAt} avec la priorite donnee. */
    public static Instant dueAt(Instant createdAt, String priority) {
        return createdAt.plus(budget(priority));
    }
}
