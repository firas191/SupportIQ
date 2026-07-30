package com.supportiq.backend.tickets;

import com.supportiq.backend.auth.User;
import com.supportiq.backend.auth.UserRepository;
import com.supportiq.backend.common.error.ResourceNotFoundException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fiche ticket et boucle human-in-the-loop (S4-J4).
 *
 * <p>Trois operations : consulter la fiche (ticket + analyse + similaires), **corriger** une
 * prediction (trace + application), **fusionner** un doublon. Les corrections alimentent la table
 * `annotations` qui servira de dataset de re-entrainement (rapport F10).
 */
@Service
public class TicketDetailService {

    /** Champs corrigeables et valeurs autorisees : ferme les deux surfaces d'injection SQL. */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "category", Set.of("TECHNIQUE", "FACTURATION", "COMPTE", "RECLAMATION", "DEMANDE"),
            "priority", Set.of("LOW", "MEDIUM", "HIGH"),
            "sentiment", Set.of("NEG", "NEU", "POS"));

    private final TicketDetailRepository repository;
    private final SimilarTicketClient similarClient;
    private final UserRepository users;

    public TicketDetailService(TicketDetailRepository repository, SimilarTicketClient similarClient,
            UserRepository users) {
        this.repository = repository;
        this.similarClient = similarClient;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public TicketDetailResponse detail(long id) {
        TicketDetailResponse base = repository.findDetail(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket introuvable : " + id));
        // Enrichissement via le service IA (degrade proprement en liste vide s'il est indisponible).
        List<TicketDetailResponse.SimilarTicket> similar = similarClient.findSimilar(id);
        return new TicketDetailResponse(
                base.id(), base.externalRef(), base.source(), base.customerEmail(), base.subject(),
                base.body(), base.language(), base.status(), base.slaDueAt(), base.createdAt(),
                base.mergedIntoId(), base.analysis(), similar);
    }

    /**
     * Enregistre une correction humaine : on **trace** (predicted -> corrected, par qui) puis on
     * applique a l'analyse courante. L'ordre importe : la trace capture la valeur predite AVANT ecrasement.
     */
    @Transactional
    public TicketDetailResponse annotate(long ticketId, AnnotationRequest request, String userEmail) {
        String field = normalizeField(request.field());
        String corrected = normalizeValue(field, request.value());

        if (!repository.exists(ticketId)) {
            throw new ResourceNotFoundException("Ticket introuvable : " + ticketId);
        }
        String predicted = repository.currentValue(ticketId, field)
                .orElseThrow(() -> new TicketStateException(
                        "Le ticket " + ticketId + " n'a pas encore d'analyse a corriger."));

        User user = users.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur courant introuvable"));

        repository.insertAnnotation(ticketId, field, predicted, corrected, user.getId());
        repository.applyCorrection(ticketId, field, corrected);
        return detail(ticketId);
    }

    /** Marque le ticket comme doublon d'un autre (fusion suggeree par la similarite vectorielle). */
    @Transactional
    public TicketDetailResponse merge(long duplicateId, long targetId) {
        if (duplicateId == targetId) {
            throw new TicketStateException("Un ticket ne peut pas etre fusionne avec lui-meme.");
        }
        TicketDetailResponse duplicate = repository.findDetail(duplicateId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket introuvable : " + duplicateId));
        if (!repository.exists(targetId)) {
            throw new ResourceNotFoundException("Ticket cible introuvable : " + targetId);
        }
        if (duplicate.mergedIntoId() != null) {
            throw new TicketStateException(
                    "Le ticket " + duplicateId + " est deja fusionne dans " + duplicate.mergedIntoId() + ".");
        }
        repository.merge(duplicateId, targetId);
        return detail(duplicateId);
    }

    private String normalizeField(String field) {
        String f = field == null ? "" : field.strip().toLowerCase(Locale.ROOT);
        if (!ALLOWED.containsKey(f)) {
            throw new IllegalArgumentException(
                    "Champ corrigeable invalide : " + field + " (attendu : category, priority ou sentiment)");
        }
        return f;
    }

    private String normalizeValue(String field, String value) {
        String v = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (!ALLOWED.get(field).contains(v)) {
            throw new IllegalArgumentException("Valeur invalide pour '" + field + "' : " + value);
        }
        return v;
    }
}
