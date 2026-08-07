package com.supportiq.backend.drafts;

import java.util.Locale;
import java.util.Set;

/**
 * Cycle de vie d'un brouillon de reponse (S5-J4).
 *
 * <pre>
 *   PROPOSED ──edit──> EDITED ──approve──> SENT      (terminal)
 *      │                  │
 *      └──approve─────────┴──reject─────> REJECTED   (terminal)
 * </pre>
 *
 * <p><b>Pourquoi une machine a etats explicite</b> plutot qu'un simple champ texte : sans elle,
 * rien n'empeche de rejeter un brouillon deja envoye, ou de rouvrir une decision prise. Le taux de
 * validation du S5-J5 se calculerait alors sur des lignes dont l'historique a ete reecrit. Les
 * transitions sont donc declarees ici, une fois, et refusees en 409 ailleurs.
 *
 * <p><b>Honnetete sur SENT</b> : la plateforme n'a aujourd'hui aucun canal d'envoi (l'e-mail
 * sortant arrive en S6-J4 avec le Digest). SENT signifie donc « valide par un humain, bon pour
 * envoi » — et l'interface dit « Approuver », pas « Envoyer ». Nommer un statut d'apres une action
 * qui n'existe pas serait un mensonge de plus dans la base de donnees.
 */
public enum DraftStatus {

    /** Genere par l'agent, personne ne l'a encore regarde. */
    PROPOSED,

    /** Un agent l'a retouche mais n'a pas encore tranche. */
    EDITED,

    /** Valide par un humain. Terminal. */
    SENT,

    /** Ecarte par un humain. Terminal — le brouillon reste en base pour la mesure. */
    REJECTED;

    /** Statuts qu'un humain peut demander. PROPOSED n'en fait pas partie : seul l'agent le pose. */
    private static final Set<DraftStatus> REVIEWABLE = Set.of(EDITED, SENT, REJECTED);

    /** Une decision prise ne se rejoue pas. */
    public boolean isTerminal() {
        return this == SENT || this == REJECTED;
    }

    public static DraftStatus parseReview(String raw) {
        String value = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        DraftStatus status;
        try {
            status = DraftStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Statut de brouillon invalide : " + raw + " (attendu : EDITED, SENT ou REJECTED)");
        }
        if (!REVIEWABLE.contains(status)) {
            throw new IllegalArgumentException(
                    "Statut de brouillon non applicable par un utilisateur : " + raw);
        }
        return status;
    }
}
