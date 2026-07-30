package com.supportiq.backend.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.supportiq.backend.realtime.RealtimeBroadcaster;
import com.supportiq.backend.realtime.RealtimeEvent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Verifie que le listener publie un message AMQP par ticket **et** diffuse un signal temps reel
 * (S4-J5), sans broker ni WebSocket : les deux collaborateurs sont mockes.
 */
class TicketEventPublisherTest {

    @Test
    void publishesOneMessageAndOneBroadcastPerTicket() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        RealtimeBroadcaster broadcaster = mock(RealtimeBroadcaster.class);
        TicketEventPublisher publisher = new TicketEventPublisher(rabbitTemplate, broadcaster);

        List<TicketCreatedEvent> tickets = List.of(
                new TicketCreatedEvent(1L, "TCK-1", "Sujet 1", "Corps 1", "fr"),
                new TicketCreatedEvent(2L, "TCK-2", "Sujet 2", "Corps 2", "en"));

        publisher.onTicketsPersisted(new TicketsPersistedEvent(tickets));

        verify(rabbitTemplate, times(2)).convertAndSend(
                eq(RabbitConfig.EXCHANGE), eq(RabbitConfig.ROUTING_KEY_CREATED), any(TicketCreatedEvent.class));
        verify(broadcaster, times(2)).ticketEvent(any(RealtimeEvent.class));
    }
}
