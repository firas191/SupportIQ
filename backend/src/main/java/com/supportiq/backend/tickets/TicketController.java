package com.supportiq.backend.tickets;

import com.supportiq.backend.common.PageResponse;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final TicketDetailService detailService;

    public TicketController(TicketQueryService queryService, TicketDetailService detailService) {
        this.queryService = queryService;
        this.detailService = detailService;
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
            // Filtre « a risque » (S7-J3) : booleen et non seuil, le seuil etant une decision
            // d'exploitation commune a toute l'equipe (ADR-0010).
            @RequestParam(required = false) Boolean atRisk,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        return queryService.search(q, status, source, language, category, priority, sentiment,
                atRisk, page, size, sort, direction);
    }

    /** Fiche complete : ticket + analyse IA + mots-cles + tickets similaires (S4-J4). */
    @GetMapping("/{id}")
    public TicketDetailResponse detail(@PathVariable long id) {
        return detailService.detail(id);
    }

    /**
     * Correction humaine d'un champ d'analyse (boucle d'active learning, F10). La correction est
     * **tracee** dans `annotations` (predit -> corrige, par qui) puis appliquee a l'analyse.
     */
    @PostMapping("/{id}/annotations")
    public TicketDetailResponse annotate(@PathVariable long id,
            @Valid @RequestBody AnnotationRequest request, Principal principal) {
        return detailService.annotate(id, request, principal.getName());
    }

    /** Fusion de doublon : ce ticket devient un doublon de `targetId` (statut MERGED). */
    @PostMapping("/{id}/merge")
    public TicketDetailResponse merge(@PathVariable long id, @Valid @RequestBody MergeRequest request) {
        return detailService.merge(id, request.targetId());
    }
}
