package com.supportiq.backend.tickets;

import com.supportiq.backend.common.PageResponse;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lecture paginée/triée/filtrée des tickets (S2-J4). Le tri est whiteliste (sécurité : {@code sort}
 * vient du client et alimente une requete SQL ; on n'autorise que des champs connus).
 */
@Service
public class TicketQueryService {

    /** Champs autorisés au tri : empeche l'injection d'une propriete arbitraire via ?sort=. */
    private static final Set<String> SORTABLE =
            Set.of("createdAt", "subject", "status", "source", "language", "id");
    private static final String DEFAULT_SORT = "createdAt";
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private final TicketRepository tickets;

    public TicketQueryService(TicketRepository tickets) {
        this.tickets = tickets;
    }

    @Transactional(readOnly = true)
    public PageResponse<TicketSummaryResponse> search(String q, String status, String source, String language,
            int page, int size, String sort, String direction) {
        Specification<Ticket> spec = TicketSpecifications.withFilters(
                q,
                parseEnum(TicketStatus.class, status, "status"),
                parseEnum(TicketSource.class, source, "source"),
                normalizeLanguage(language));

        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size), buildSort(sort, direction));
        Page<TicketSummaryResponse> result = tickets.findAll(spec, pageable).map(TicketSummaryResponse::from);
        return PageResponse.of(result);
    }

    private int clampSize(int size) {
        if (size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    private Sort buildSort(String sort, String direction) {
        String field = (sort != null && SORTABLE.contains(sort)) ? sort : DEFAULT_SORT;
        Sort.Direction dir = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(dir, field);
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Valeur invalide pour le filtre '" + field + "' : " + value);
        }
    }

    private String normalizeLanguage(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String lang = v.strip().toLowerCase(Locale.ROOT);
        return (lang.equals("fr") || lang.equals("en")) ? lang : null;
    }
}
