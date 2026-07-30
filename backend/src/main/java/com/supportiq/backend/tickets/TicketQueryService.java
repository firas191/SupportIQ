package com.supportiq.backend.tickets;

import com.supportiq.backend.common.PageResponse;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lecture paginee/triee/filtree des tickets, avec recherche full-text (S4-J3).
 *
 * <p>Le service valide et normalise les entrees (enums, langue, bornes de pagination) puis delegue
 * au {@link TicketSearchRepository} qui execute la recherche full-text + filtres en SQL natif.
 */
@Service
public class TicketQueryService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;
    private static final Set<String> CATEGORIES =
            Set.of("TECHNIQUE", "FACTURATION", "COMPTE", "RECLAMATION", "DEMANDE");
    private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH");
    private static final Set<String> SENTIMENTS = Set.of("NEG", "NEU", "POS");

    private final TicketSearchRepository searchRepository;

    public TicketQueryService(TicketSearchRepository searchRepository) {
        this.searchRepository = searchRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<TicketSummaryResponse> search(String q, String status, String source, String language,
            String category, String priority, String sentiment,
            int page, int size, String sort, String direction) {
        TicketSearchCriteria criteria = new TicketSearchCriteria(
                q,
                parseEnum(TicketStatus.class, status, "status"),
                parseEnum(TicketSource.class, source, "source"),
                normalizeLanguage(language),
                validate(category, CATEGORIES, "category"),
                validate(priority, PRIORITIES, "priority"),
                validate(sentiment, SENTIMENTS, "sentiment"),
                Math.max(page, 0),
                clampSize(size),
                sort,
                direction);
        return searchRepository.search(criteria);
    }

    private int clampSize(int size) {
        if (size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
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

    /** Valide une valeur d'analyse contre sa liste autorisee (les valeurs sont liees, pas concatenees). */
    private String validate(String value, Set<String> allowed, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String upper = value.strip().toUpperCase(Locale.ROOT);
        if (!allowed.contains(upper)) {
            throw new IllegalArgumentException("Valeur invalide pour le filtre '" + field + "' : " + value);
        }
        return upper;
    }

    private String normalizeLanguage(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String lang = v.strip().toLowerCase(Locale.ROOT);
        return (lang.equals("fr") || lang.equals("en")) ? lang : null;
    }
}
