package com.supportiq.backend.messaging;

import java.util.List;

/**
 * Evenement applicatif interne (Spring) emis a la fin du confirm. Un listener transactionnel
 * le convertit en messages RabbitMQ **apres le commit** (pas de message fantome si rollback).
 */
public record TicketsPersistedEvent(List<TicketCreatedEvent> tickets) {
}
