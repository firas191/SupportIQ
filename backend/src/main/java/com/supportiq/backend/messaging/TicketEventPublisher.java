package com.supportiq.backend.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publie les messages `ticket.created` vers RabbitMQ, **uniquement apres le commit** de la
 * transaction d'insertion (AFTER_COMMIT). Si la publication echoue, les tickets sont deja en base
 * mais les messages ne partent pas : on logue (un outbox transactionnel serait le durcissement prod).
 */
@Component
public class TicketEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TicketEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public TicketEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTicketsPersisted(TicketsPersistedEvent event) {
        try {
            for (TicketCreatedEvent ticket : event.tickets()) {
                rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.ROUTING_KEY_CREATED, ticket);
            }
            log.info("Publie {} evenement(s) ticket.created", event.tickets().size());
        } catch (Exception e) {
            log.error("Echec de publication ticket.created (tickets committes, messages non envoyes)", e);
        }
    }
}
