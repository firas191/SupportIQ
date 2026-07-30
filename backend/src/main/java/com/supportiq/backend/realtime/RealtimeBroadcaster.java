package com.supportiq.backend.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Diffusion des evenements temps reel vers les clients WebSocket (S4-J5).
 *
 * <p>Resilient : une erreur de diffusion ne doit jamais faire echouer l'operation metier qui l'a
 * declenchee (un ticket cree reste cree meme si personne ne peut etre notifie).
 */
@Component
public class RealtimeBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(RealtimeBroadcaster.class);

    private final SimpMessagingTemplate messaging;

    public RealtimeBroadcaster(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    public void ticketEvent(RealtimeEvent event) {
        send(WebSocketConfig.TOPIC_TICKETS, event);
    }

    public void alert(Object payload) {
        send(WebSocketConfig.TOPIC_ALERTS, payload);
    }

    private void send(String destination, Object payload) {
        try {
            messaging.convertAndSend(destination, payload);
        } catch (Exception e) {  // noqa - la diffusion est best-effort
            log.warn("Diffusion temps reel echouee sur {} : {}", destination, e.getMessage());
        }
    }
}
