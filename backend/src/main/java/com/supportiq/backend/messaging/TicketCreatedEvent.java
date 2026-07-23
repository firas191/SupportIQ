package com.supportiq.backend.messaging;

/**
 * Message publie sur RabbitMQ a la creation d'un ticket. Auto-porteur (sujet/corps/langue) pour
 * que le consommateur analyse sans re-lire la base ; external_ref sert a l'idempotence.
 */
public record TicketCreatedEvent(
        Long ticketId,
        String externalRef,
        String subject,
        String body,
        String language) {
}
