package com.supportiq.backend.tickets;

import com.supportiq.backend.common.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liste et recherche des tickets (rapport §6). Pagination/tri/filtres cote serveur — la table
 * Angular ne charge jamais tout.
 *
 * <p>S4-J3 : `q` fait une **recherche full-text** (tsvector FR/EN, index GIN) et bascule le tri sur
 * la **pertinence** (`ts_rank`) ; les filtres d'analyse (category/priority/sentiment) sont
 * desormais disponibles. Accessible a tout utilisateur authentifie (AGENT+, rapport §7).
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketQueryService queryService;

    public TicketController(TicketQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public PageResponse<TicketSummaryResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String sentiment,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        return queryService.search(q, status, source, language, category, priority, sentiment,
                page, size, sort, direction);
    }
}
