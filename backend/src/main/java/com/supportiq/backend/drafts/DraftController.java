package com.supportiq.backend.drafts;

import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Brouillons de reponse assistes (S5-J4, rapport §6).
 *
 * <p><b>Reserve aux AGENT+</b>, c'est-a-dire a tout utilisateur authentifie : rediger une reponse
 * client est le metier de l'agent, pas un privilege d'administration. La restriction utile n'est pas
 * sur le role mais sur l'action — aucun endpoint ici n'envoie quoi que ce soit au client.
 *
 * <p><b>Deux racines dans un meme controleur</b>, volontairement. Un brouillon appartient a un
 * ticket tant qu'on le demande ({@code /api/tickets/{id}/draft}) mais devient une ressource a part
 * entiere une fois cree ({@code /api/drafts/{id}}) : la revue porte sur <i>ce</i> brouillon, pas sur
 * le dernier en date du ticket. Les router par le ticket ouvrirait une course — deux agents
 * regardant la meme fiche pendant qu'un troisieme regenere valideraient un texte qu'ils n'ont pas lu.
 */
@RestController
@PreAuthorize("hasRole('AGENT')")
public class DraftController {

    private final DraftService service;

    public DraftController(DraftService service) {
        this.service = service;
    }

    /**
     * Dernier brouillon exploitable du ticket.
     *
     * <p>204 quand il n'y en a pas : l'absence de brouillon est un etat normal (personne n'en a
     * encore demande), pas une erreur. Un 404 obligerait l'interface a traiter le cas nominal dans
     * son gestionnaire d'erreur.
     */
    @GetMapping("/api/tickets/{ticketId}/draft")
    public ResponseEntity<DraftView> latest(@PathVariable long ticketId) {
        return service.latest(ticketId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Demande un brouillon. Rappeler cet endpoint en produit un nouveau (l'ancien reste en base). */
    @PostMapping("/api/tickets/{ticketId}/draft")
    public DraftView generate(@PathVariable long ticketId,
            @RequestBody(required = false) GenerateDraftRequest request) {
        String tone = request == null ? "formal" : request.tone();
        return service.generate(ticketId, tone);
    }

    /**
     * Decision humaine : corriger, valider ou ecarter. C'est la garantie du rapport §5.2.
     *
     * <p>Valider declenche l'envoi au client <b>si celui-ci est active sur le serveur</b>. La
     * reponse renvoyee porte l'etat de livraison ({@code deliveredAt}, {@code deliveryError}) :
     * l'appel reussit meme si le courriel echoue, parce que la decision, elle, est acquise.
     */
    @PatchMapping("/api/drafts/{draftId}")
    public DraftView review(@PathVariable long draftId,
            @Valid @RequestBody ReviewDraftRequest request, Principal principal) {
        return service.review(draftId, request.status(), request.content(), principal.getName());
    }

    /** Rejoue l'envoi d'une reponse deja validee, apres un echec de livraison. */
    @PostMapping("/api/drafts/{draftId}/send")
    public DraftView resend(@PathVariable long draftId) {
        return service.resend(draftId);
    }
}
