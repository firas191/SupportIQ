package com.supportiq.backend.tickets;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filtres dynamiques de la liste de tickets (S2-J4). Chaque critere absent est ignoré (predicat non
 * ajouté), ce qui evite les branches "(:x is null or ...)" partout et garde des requetes propres.
 *
 * <p>La recherche texte est un LIKE insensible a la casse sur subject/body — suffisant pour le J4.
 * L'index GIN full-text FR/EN (rapport §4) sera pose en S4 pour la vraie recherche performante.
 */
final class TicketSpecifications {

    private TicketSpecifications() {
    }

    static Specification<Ticket> withFilters(String q, TicketStatus status, TicketSource source, String language) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (source != null) {
                predicates.add(cb.equal(root.get("source"), source));
            }
            if (language != null) {
                predicates.add(cb.equal(root.get("language"), language));
            }
            if (q != null && !q.isBlank()) {
                String like = "%" + q.strip().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("subject")), like),
                        cb.like(cb.lower(root.get("body")), like)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
