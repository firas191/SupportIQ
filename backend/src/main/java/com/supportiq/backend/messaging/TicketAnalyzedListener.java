package com.supportiq.backend.messaging;

import com.supportiq.backend.realtime.RealtimeBroadcaster;
import com.supportiq.backend.realtime.RealtimeEvent;
import com.supportiq.backend.sla.SlaRepository;
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
    private final SlaRepository sla;

    public TicketAnalyzedListener(RealtimeBroadcaster broadcaster, SlaRepository sla) {
        this.broadcaster = broadcaster;
        this.sla = sla;
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

        // L'echeance SLA depend de la priorite, qui n'est connue qu'ici (S7-J3). C'est le bon
        // moment et le bon endroit : le message arrive apres le commit de l'analyse, et ce
        // listener existe deja — pas de nouvelle file, pas de nouveau consommateur.
        //
        // Best-effort, comme la diffusion : une echeance non posee laisse le ticket sur celle de
        // la politique par defaut (V17), ce qui est degrade mais correct. Faire echouer le
        // message renverrait l'analyse en DLQ pour un detail d'ordonnancement.
        if (event.ticketId() != null) {
            try {
                sla.applyDueDate(event.ticketId(), event.priority());
            } catch (RuntimeException e) {
                log.warn("Echeance SLA non posee sur le ticket {} : {}",
                        event.ticketId(), e.getMessage());
            }
        }

        broadcaster.ticketEvent(event);
        log.debug("Analyse diffusee en temps reel : ticket {} ({})", event.ticketId(), event.category());
    }
}
