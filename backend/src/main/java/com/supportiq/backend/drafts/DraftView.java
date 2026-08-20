package com.supportiq.backend.drafts;

import java.time.Instant;
import java.util.List;

/**
 * Brouillon de reponse tel que l'interface le consomme (S5-J4).
 *
 * <p>Deux textes plutot qu'un : {@code content} est la sortie du modele, {@code finalContent} la
 * version corrigee par un humain (null tant que personne n'a touche au texte). Voir V10 pour la
 * raison — un juge automatique qui noterait un texte reecrit par un agent ne mesurerait plus le
 * modele.
 */
public record DraftView(
        Long id,
        Long ticketId,
        String content,
        String finalContent,
        List<Citation> citations,
        DraftStatus status,
        String tone,
        boolean lowConfidence,
        /*
         * Le modele a reconnu que la documentation ne couvre pas la demande. C'est un **resultat
         * correct**, pas un echec : l'interface affiche « rien a proposer » et masque les actions
         * d'envoi, au lieu d'alerter. Melanger les deux apprend a ignorer les vraies alertes.
         */
        boolean abstained,
        List<String> issues,
        int attempts,
        Instant createdAt,
        Instant reviewedAt,
        String reviewedBy,
        /*
         * Livraison au client — **distincte de la decision humaine**. `status = SENT` avec
         * `deliveredAt = null` signifie « valide, mais jamais parti » : c'est un etat reel qui doit
         * rester visible, sans quoi un agent croirait le client repondu.
         */
        Instant deliveredAt,
        String deliveredTo,
        String deliveryError,
        /*
         * L'envoi de reponses est-il actif sur ce serveur ? Porte par chaque brouillon plutot que
         * par un endpoint de configuration : la valeur est globale, mais la fiche ticket est
         * l'ecran le plus ouvert de l'application et un second appel par consultation coûterait
         * plus que ce booleen. Il decide d'un seul libelle — « Valider » ou « Valider et
         * envoyer » — et ce libelle ne doit jamais promettre plus que ce que le serveur fait.
         */
        boolean replyEnabled) {

    /**
     * Source d'une affirmation du brouillon.
     *
     * <p>{@code content} porte le passage <b>entier</b> tel qu'il est indexe, pas l'extrait tronque
     * a 280 caracteres stocke dans le jsonb : une troncature peut couper exactement la clause qui
     * nuance l'affirmation (« sous reserve que… »), et l'agent validerait alors sur une source
     * amputee. L'extrait reste le repli quand le fragment n'existe plus (les identifiants changent
     * a chaque re-import de document — constate en S5-J2).
     */
    public record Citation(
            int marker,
            Long chunkId,
            String source,
            String heading,
            String content,
            boolean stale) {
    }
}
