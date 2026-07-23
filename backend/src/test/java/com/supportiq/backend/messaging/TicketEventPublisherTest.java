package com.supportiq.backend.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Verifie que le listener publie un message par ticket, sans broker (RabbitTemplate mocke). */
class TicketEventPublisherTest {

    @Test
    void publishesOneMessagePerTicket() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        TicketEventPublisher publisher = new TicketEventPublisher(rabbitTemplate);

        List<TicketCreatedEvent> tickets = List.of(
                new TicketCreatedEvent(1L, "TCK-1", "Sujet 1", "Corps 1", "fr"),
                new TicketCreatedEvent(2L, "TCK-2", "Sujet 2", "Corps 2", "en"));

        publisher.onTicketsPersisted(new TicketsPersistedEvent(tickets));

        verify(rabbitTemplate, times(2)).convertAndSend(
                eq(RabbitConfig.EXCHANGE), eq(RabbitConfig.ROUTING_KEY_CREATED), any(TicketCreatedEvent.class));
    }
}
