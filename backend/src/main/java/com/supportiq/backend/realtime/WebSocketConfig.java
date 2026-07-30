package com.supportiq.backend.realtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Temps reel (S4-J5, rapport §6 : `WS /ws`, topics `/topic/tickets` et `/topic/alerts`).
 *
 * <p>Broker **simple en memoire** : suffisant ici (diffusion de notifications a tous les clients
 * connectes, pas de messagerie point-a-point ni de persistance). En multi-instance il faudrait un
 * relais STOMP externe (RabbitMQ a deja le plugin) pour que tous les nœuds diffusent — porte de sortie.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    public static final String TOPIC_TICKETS = "/topic/tickets";
    public static final String TOPIC_ALERTS = "/topic/alerts";

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // WebSocket natif (pas de SockJS : @stomp/stompjs cote client s'en passe).
        // CORS : le frontend dev passe par le proxy Angular, mais on autorise explicitement
        // l'origine locale pour un acces direct pendant les tests.
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("http://localhost:4200", "http://127.0.0.1:4200");
    }
}
