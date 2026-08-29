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

    /**
     * Echeance <b>provisoire</b>, posee des la creation, avant toute analyse (S8-J1).
     *
     * <p>Pourquoi elle existe : l'echeance n'etait calculee qu'a l'analyse, la priorite n'etant
     * connue qu'a ce moment-la. La consequence n'avait pas ete anticipee — <b>un ticket jamais
     * analyse n'avait jamais d'echeance</b>, donc il ne pouvait ni apparaitre a risque, ni compter
     * comme depasse.
     *
     * <p>Et l'exclusion etait doublement silencieuse : le lot de scoring trie par
     * {@code sla_due_at DESC NULLS LAST} avec un plafond, donc les tickets sans echeance ne sont
     * jamais atteints ; et la liste applique le meme {@code NULLS LAST}. Un ticket sortait du
     * dispositif SLA sans qu'aucune requete ne renvoie d'erreur, aucun compteur ne bouge, aucun
     * journal ne s'ecrive. Exactement le mode de defaillance que le rattrapage d'analyse traite par
     * ailleurs, sur un autre chemin.
     *
     * <p>Le budget retenu est celui du courant (24 h), pour la meme raison que {@link #budget} pour
     * une priorite inconnue : traiter l'inconnu comme urgent ferait basculer toute la file en rouge,
     * ce qui revient a n'avoir plus aucune urgence.
     */
    public static Instant provisionalDueAt(Instant createdAt) {
        return createdAt.plus(MEDIUM);
    }
}
