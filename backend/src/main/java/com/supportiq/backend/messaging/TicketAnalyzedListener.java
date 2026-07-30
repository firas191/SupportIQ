package com.supportiq.backend.messaging;

import com.supportiq.backend.realtime.RealtimeBroadcaster;
import com.supportiq.backend.realtime.RealtimeEvent;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consomme `ticket.analyzed` publie par FastAPI et le rediffuse en WebSocket (S4-J5).
 *
 * <p>C'est ce listener qui **ferme la boucle asynchrone** du rapport §3 :
 * Spring publie `ticket.created` -> FastAPI analyse -> FastAPI publie `ticket.analyzed` ->
 * Spring notifie l'UI. Le frontend voit donc l'analyse apparaitre sans rechargement.
 */
@Component
public class TicketAnalyzedListener {

    private static final Logger log = LoggerFactory.getLogger(TicketAnalyzedListener.class);

    private final RealtimeBroadcaster broadcaster;

    public TicketAnalyzedListener(RealtimeBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @RabbitListener(queues = RabbitConfig.QUEUE_ANALYZED)
    public void onTicketAnalyzed(Map<String, Object> payload) {
        Number ticketId = (Number) payload.get("ticketId");
        RealtimeEvent event = RealtimeEvent.analyzed(
                ticketId == null ? null : ticketId.longValue(),
                (String) payload.get("externalRef"),
                (String) payload.get("category"),
                (String) payload.get("priority"),
                (String) payload.get("sentiment"));
        broadcaster.ticketEvent(event);
        log.debug("Analyse diffusee en temps reel : ticket {} ({})", event.ticketId(), event.category());
    }
}
