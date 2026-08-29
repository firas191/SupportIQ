package com.supportiq.backend.drafts;

import com.supportiq.backend.auth.User;
import com.supportiq.backend.auth.UserRepository;
import com.supportiq.backend.common.error.AiServiceException;
import com.supportiq.backend.common.error.ResourceNotFoundException;
import com.supportiq.backend.tickets.TicketRepository;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Brouillons de reponse : generation, consultation, validation humaine (S5-J4).
 *
 * <p>C'est le point ou la plateforme cesse d'analyser pour proposer d'agir — et donc le point ou la
 * garantie du rapport §5.2 doit tenir : <b>rien ne part sans qu'un humain ait tranche</b>. Cette
 * classe ne possede aucun chemin qui marque un brouillon comme valide sans passer par
 * {@link #review}, qui exige un utilisateur authentifie.
 */
@Service
public class DraftService {

    private static final Set<String> TONES = Set.of("formal", "empathetic");

    private final DraftRepository repository;
    private final DraftClient client;
    private final TicketRepository tickets;
    private final UserRepository users;
    private final ReplyMailer replyMailer;

    public DraftService(DraftRepository repository, DraftClient client, TicketRepository tickets,
            UserRepository users, ReplyMailer replyMailer) {
        this.repository = repository;
        this.client = client;
        this.tickets = tickets;
        this.users = users;
        this.replyMailer = replyMailer;
    }

    /** Dernier brouillon exploitable, ou vide si aucun n'a encore ete demande. */
    @Transactional(readOnly = true)
    public Optional<DraftView> latest(long ticketId) {
        requireTicket(ticketId);
        return repository.findLatest(ticketId);
    }

    /**
     * Demande un nouveau brouillon.
     *
     * <p><b>Sans transaction</b> — et c'est deliberé. L'appel dure jusqu'a deux minutes ; l'englober
     * ouvrirait une transaction PostgreSQL pendant tout ce temps, pour rigoureusement aucune
     * ecriture de notre cote (l'agent ecrit lui-meme). Tenir une connexion ouverte a ne rien faire
     * est la meilleure facon d'epuiser le pool.
     *
     * <p>Chaque appel <b>ajoute</b> un brouillon, jamais ne remplace : l'historique des
     * re-generations est ce qui permettra de mesurer, en S5-J5, combien de fois il a fallu s'y
     * reprendre. Le panneau n'affiche que le dernier.
     */
    public DraftView generate(long ticketId, String rawTone) {
        requireTicket(ticketId);
        String tone = normalizeTone(rawTone);

        Long draftId = client.generate(ticketId, tone);
        if (draftId == null) {
            // L'agent a produit un texte mais n'a pas pu l'enregistrer (base indisponible cote IA).
            // On ne renvoie pas ce texte volatil : un brouillon qu'on ne peut ni retrouver ni
            // valider n'est pas exploitable dans une boucle de validation.
            throw AiServiceException.draft(502, "Le brouillon n'a pas pu etre enregistre");
        }
        return repository.findById(draftId)
                .orElseThrow(() -> AiServiceException.draft(502, "Brouillon introuvable apres generation"));
    }

    /**
     * Enregistre la decision humaine sur un brouillon.
     *
     * @param content texte corrige, ou {@code null} pour valider tel quel. Il n'ecrase jamais la
     *     sortie du modele : il est ecrit dans une colonne distincte (V10).
     */
    @Transactional
    public DraftView review(long draftId, String rawStatus, String content, String userEmail) {
        DraftStatus target = DraftStatus.parseReview(rawStatus);

        DraftView current = repository.findById(draftId)
                .orElseThrow(() -> new ResourceNotFoundException("Brouillon introuvable : " + draftId));

        if (current.status().isTerminal()) {
            throw new DraftStateException(
                    "Ce brouillon a deja ete " + (current.status() == DraftStatus.SENT ? "valide" : "ecarte")
                            + " : la decision ne se rejoue pas.");
        }
        if (current.abstained() && target == DraftStatus.SENT) {
            // Garde-fou de fond, pas de confort : le texte d'abstention s'adresse a l'agent (« a
            // traiter manuellement »), pas au client. Le masquer dans l'interface ne suffit pas —
            // une regle qui n'existe qu'en CSS n'est pas une regle.
            throw new DraftStateException(
                    "Il n'y a rien a valider : la documentation ne couvre pas cette demande.");
        }

        User reviewer = users.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur courant introuvable"));

        String finalContent = normalizeContent(content, current);
        if (target == DraftStatus.EDITED && finalContent == null) {
            throw new IllegalArgumentException(
                    "Aucune modification a enregistrer : le texte est identique au brouillon.");
        }

        repository.review(draftId, target, finalContent, reviewer.getId());

        // La validation est enregistree AVANT toute tentative d'envoi. Si le serveur de courriel
        // echoue, la decision de l'agent reste acquise — il ne refera pas le travail.
        if (target == DraftStatus.SENT) {
            deliver(draftId);
        }
        return repository.findById(draftId).orElseThrow();
    }

    /**
     * Rejoue l'envoi d'une reponse deja validee.
     *
     * <p>Existe parce qu'un echec de livraison est frequent et reparable : serveur indisponible,
     * adresse temporairement refusee. Sans ce point d'entree, la seule issue serait de recopier le
     * texte a la main dans un client de messagerie.
     */
    public DraftView resend(long draftId) {
        DraftView draft = repository.findById(draftId)
                .orElseThrow(() -> new ResourceNotFoundException("Brouillon introuvable : " + draftId));
        if (draft.status() != DraftStatus.SENT) {
            throw new DraftStateException("Seule une reponse validee peut etre envoyee.");
        }
        if (!replyMailer.enabled()) {
            // Un renvoi demande explicitement doit dire pourquoi il ne part pas. Ne rien faire en
            // silence donnerait un succes apparent — le defaut exact corrige sur le digest hier.
            throw AiServiceException.draft(503, "L'envoi de reponses au client n'est pas active");
        }
        deliver(draftId);
        return repository.findById(draftId).orElseThrow();
    }

    /**
     * Envoi au client, tolerant et trace.
     *
     * <p>Aucune exception ne remonte : l'appelant a deja obtenu ce qu'il demandait — la decision
     * est enregistree. Ce qui reste a dire, c'est <b>si le message est parti</b>, et cela se lit
     * dans {@code deliveredAt} / {@code deliveryError}, que l'interface affiche.
     *
     * <p>Faire echouer la requete HTTP serait pire : l'agent verrait une erreur, croirait sa
     * validation perdue, et rejouerait une action deja effectuee.
     */
    private void deliver(long draftId) {
        if (!replyMailer.enabled()) {
            // Envoi non configure : ce n'est pas un echec, c'est un mode de fonctionnement. On ne
            // remplit pas `delivery_error`, qui signalerait un probleme a corriger.
            return;
        }

        DraftRepository.Recipient recipient = repository.recipientOf(draftId).orElse(null);
        if (recipient == null || recipient.email() == null || recipient.email().isBlank()) {
            // Un ticket importe par fichier n'a pas toujours d'adresse. On ne devine pas, et on le
            // dit — sinon l'agent croirait la reponse partie.
            repository.markDeliveryFailed(draftId, "Ce ticket n'a pas d'adresse client connue.");
            return;
        }

        DraftView draft = repository.findById(draftId).orElseThrow();
        String body = draft.finalContent() != null ? draft.finalContent() : draft.content();

        try {
            replyMailer.send(recipient.email(), recipient.subject(), body);
            repository.markDelivered(draftId, recipient.email());
        } catch (RuntimeException e) {
            repository.markDeliveryFailed(draftId, e.getMessage());
        }
    }

    /* --- Interne ------------------------------------------------------------ */

    /**
     * Ne conserve le texte que s'il apporte quelque chose : non vide, et different de ce qui est
     * deja enregistre. Sans ce filtre, valider sans rien changer remplirait {@code final_content}
     * d'une copie de {@code content} et effacerait la distinction « valide tel quel » / « valide
     * apres reecriture » — precisement la mesure que la colonne existe pour porter.
     */
    private String normalizeContent(String content, DraftView current) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String cleaned = content.strip();
        String reference = current.finalContent() != null ? current.finalContent() : current.content();
        return cleaned.equals(reference.strip()) ? null : cleaned;
    }

    private String normalizeTone(String tone) {
        String value = tone == null ? "formal" : tone.strip().toLowerCase(Locale.ROOT);
        if (!TONES.contains(value)) {
            throw new IllegalArgumentException(
                    "Ton invalide : " + tone + " (attendu : formal ou empathetic)");
        }
        return value;
    }

    private void requireTicket(long ticketId) {
        if (!tickets.existsById(ticketId)) {
            throw new ResourceNotFoundException("Ticket introuvable : " + ticketId);
        }
    }
}
