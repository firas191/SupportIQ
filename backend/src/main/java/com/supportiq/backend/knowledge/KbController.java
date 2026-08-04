package com.supportiq.backend.knowledge;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Base de connaissances — API d'administration (S5-J1).
 *
 * <p>Reservee aux <b>administrateurs</b> : le contenu de la KB determine ce que l'agent Resolution
 * repondra aux clients en S5-J3. Un document errone s'y propagerait en reponses erronees a grande
 * echelle — c'est un pouvoir d'ecriture beaucoup plus large que la correction d'un ticket.
 *
 * <p>La recherche est ouverte aux <b>AGENT+</b> : elle sert a verifier ce que la base contient sur
 * un sujet, et ne modifie rien.
 */
@RestController
@RequestMapping("/api/kb")
public class KbController {

    private final KbService service;

    public KbController(KbService service) {
        this.service = service;
    }

    /** Liste des documents indexes, avec l'etat de vectorisation de chacun. */
    @GetMapping("/documents")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> documents() {
        List<KbDocumentResponse> documents = service.documents();
        return Map.of(
                "documents", documents,
                "totalDocuments", documents.size(),
                "totalChunks", service.totalChunks());
    }

    /** Indexe un document (Markdown, texte ou PDF). Re-envoyer le meme nom remplace ses fragments. */
    @PostMapping("/documents")
    @PreAuthorize("hasRole('ADMIN')")
    public KbIngestResponse ingest(@RequestParam("file") MultipartFile file) {
        return service.ingest(file);
    }

    /**
     * Supprime un document et tous ses fragments.
     *
     * <p>{@code :.+} dans le gabarit : sans cela Spring tronque le nom a la derniere extension et
     * {@code faq-facturation.md} arriverait en {@code faq-facturation}.
     */
    @DeleteMapping("/documents/{source:.+}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable String source) {
        service.delete(source);
        return ResponseEntity.noContent().build();
    }

    /** Recalcule les vecteurs manquants ; {@code force=true} recalcule tout (changement de modele). */
    @PostMapping("/reindex")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Integer> reindex(@RequestParam(defaultValue = "false") boolean force) {
        return Map.of("processed", service.reindex(force));
    }

    /** Interroge la base — c'est le livrable « KB interrogeable » du J1. */
    @PostMapping("/search")
    @PreAuthorize("hasRole('AGENT')")
    public List<KbChunkResponse> search(@Valid @RequestBody KbSearchRequest request) {
        return service.search(request);
    }
}
