package com.supportiq.backend.intake;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Contrats de l'ingestion documentaire (S7-J4).
 *
 * <p>Regroupes dans un seul fichier : ce sont quatre records de trois lignes qui n'ont de sens
 * qu'ensemble. Les eclater en quatre fichiers rendrait le module moins lisible, pas plus modulaire.
 */
public final class IntakeModels {

    /** Plafond de securite : au-dela, l'ecran de validation ne serait plus relu. */
    public static final int MAX_BATCH = 50;

    private IntakeModels() {
    }

    /**
     * Confiance par champ, telle que produite par le service IA.
     *
     * <p>Elle traverse le plan de controle **sans etre interpretee** : c'est l'interface qui decide
     * ce qu'elle surligne. Poser un seuil ici le figerait pour tous les clients.
     */
    public record FieldConfidence(double subject, double body, double customerEmail) {
    }

    /** Une demande proposee, avant toute insertion. */
    public record ProposedTicket(
            String subject,
            String body,
            String customerEmail,
            String language,
            FieldConfidence confidence) {
    }

    /**
     * Resultat de l'extraction.
     *
     * @param pages nombre de pages du document, et {@code method} l'origine du texte
     *     ({@code native}, {@code ocr}, {@code plain}). Les deux sont affiches : un lot issu d'OCR
     *     merite une relecture plus attentive, et l'agent doit savoir lequel il relit.
     */
    public record ExtractionResult(List<ProposedTicket> tickets, int pages, String method) {
    }

    /**
     * Demande de creation apres relecture humaine.
     *
     * <p>Le lot **revient du navigateur**, il n'est pas relu depuis un stockage serveur. C'est un
     * ecart delibere avec l'import de fichier (S2-J2), qui stocke le fichier et le re-parse au
     * confirm : un CSV de 10 000 lignes ne peut pas transiter par le navigateur, une douzaine de
     * demandes deja affichees a l'ecran, si. Persister ce lot creerait un troisieme cycle de vie
     * (propose / confirme / abandonne) pour un objet qui vit quatre-vingt-dix secondes.
     *
     * <p>Consequence a assumer : le client peut poster des tickets qu'aucun document ne contenait.
     * C'est acceptable parce que la creation de tickets est precisement ce que cet endpoint
     * autorise, qu'il est authentifie et soumis au RBAC — et que les entrees sont validees ici.
     */
    public record ConfirmRequest(
            @NotEmpty(message = "Le lot est vide.")
            @Size(max = MAX_BATCH, message = "Lot trop volumineux.")
            @Valid List<ConfirmedTicket> tickets) {
    }

    public record ConfirmedTicket(
            @NotBlank(message = "Le sujet est requis.")
            @Size(max = 500) String subject,
            String body,
            @Size(max = 320) String customerEmail,
            @Size(max = 2) String language) {
    }

    /** Compte rendu de l'insertion. */
    public record ConfirmResult(int created, int skipped, List<Long> ticketIds) {
    }
}
