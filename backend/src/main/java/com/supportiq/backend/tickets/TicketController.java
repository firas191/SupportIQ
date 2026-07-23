package com.supportiq.backend.tickets;

import com.supportiq.backend.common.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liste des tickets (S2-J4). Pagination/tri/filtres cote serveur — la table Angular ne charge
 * jamais tout. Accessible a tout utilisateur authentifie (AGENT et au-dessus, rapport §7).
 *
 * <p>Filtres du J4 : recherche texte + status/source/language. Les filtres category/priority/
 * sentiment (rapport §6) dependent de la table `analyses` et arriveront en S3.
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
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        return queryService.search(q, status, source, language, page, size, sort, direction);
    }
}
