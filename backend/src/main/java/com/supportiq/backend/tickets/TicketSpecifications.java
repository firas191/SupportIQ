package com.supportiq.backend.tickets;

/**
 * <strong>Obsolete depuis S4-J3.</strong> Les filtres de la liste etaient exprimes en JPA
 * Specifications avec une recherche `LIKE` sur subject/body. La recherche full-text (tsvector +
 * `ts_rank`) n'etant pas exprimable proprement via l'API Criteria (et Hibernate ne mappant pas le
 * type `tsvector`), la recherche est desormais dans {@link TicketSearchRepository} en SQL natif.
 *
 * <p>Classe conservee vide comme trace de la decision (voir CLAUDE.md §5, ecarts S4-J3) ; elle sera
 * supprimee lors du prochain nettoyage de branche.
 */
final class TicketSpecifications {

    private TicketSpecifications() {
    }
}
