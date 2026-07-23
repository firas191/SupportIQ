package com.supportiq.backend.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supportiq.backend.messaging.TicketCreatedEvent;
import com.supportiq.backend.messaging.TicketsPersistedEvent;
import com.supportiq.backend.tickets.Ticket;
import com.supportiq.backend.tickets.TicketRepository;
import com.supportiq.backend.tickets.TicketSource;
import com.supportiq.backend.tickets.TicketStatus;
import java.util.List;
import java.util.Locale;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingestion temps reel d'un ticket via webhook (S2-J4). Verifie l'authenticite (cle API + HMAC),
 * cree le ticket (source WEBHOOK) et, comme l'import (J3), publie {@code ticket.created}
 * <strong>apres commit</strong> pour l'analyse asynchrone. Idempotent par external_ref.
 */
@Service
public class WebhookService {

    private final WebhookSignatureVerifier verifier;
    private final TicketRepository tickets;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public WebhookService(WebhookSignatureVerifier verifier, TicketRepository tickets,
            ApplicationEventPublisher eventPublisher, ObjectMapper objectMapper) {
        this.verifier = verifier;
        this.tickets = tickets;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WebhookResponse ingest(String apiKey, String signature, byte[] rawBody) {
        verifier.verify(apiKey, signature, rawBody);
        WebhookTicketRequest payload = parse(rawBody);
        validate(payload);

        String ref = blankToNull(payload.externalRef());
        if (ref != null) {
            var existingId = tickets.findIdByExternalRef(ref);
            if (existingId.isPresent()) {
                return WebhookResponse.duplicate(existingId.get());
            }
        }

        Ticket saved = tickets.save(Ticket.builder()
                .externalRef(ref)
                .source(TicketSource.WEBHOOK)
                .status(TicketStatus.NEW)
                .customerEmail(blankToNull(payload.customerEmail()))
                .subject(payload.subject().trim())
                .body(blankToNull(payload.body()))
                .language(normalizeLanguage(payload.language()))
                .build());

        // Meme chaine asynchrone que l'import : publication APRES commit (TicketEventPublisher).
        TicketCreatedEvent event = new TicketCreatedEvent(saved.getId(), saved.getExternalRef(),
                saved.getSubject(), saved.getBody(), saved.getLanguage());
        eventPublisher.publishEvent(new TicketsPersistedEvent(List.of(event)));

        return WebhookResponse.accepted(saved.getId());
    }

    private WebhookTicketRequest parse(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            throw new WebhookPayloadException("Corps de requete absent.");
        }
        try {
            return objectMapper.readValue(rawBody, WebhookTicketRequest.class);
        } catch (Exception e) {
            throw new WebhookPayloadException("JSON malforme ou champs invalides.");
        }
    }

    private void validate(WebhookTicketRequest payload) {
        if (payload == null || payload.subject() == null || payload.subject().isBlank()) {
            throw new WebhookPayloadException("Le champ 'subject' est requis.");
        }
    }

    private String normalizeLanguage(String v) {
        if (v == null) {
            return null;
        }
        String lang = v.toLowerCase(Locale.ROOT);
        return (lang.equals("fr") || lang.equals("en")) ? lang : null;
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}
