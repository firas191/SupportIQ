package com.supportiq.backend.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topologie RabbitMQ (S2-J3). Spring publie `ticket.created` sur l'exchange ; la queue
 * `tickets.analyze` (consommee par FastAPI) route ses echecs vers une dead-letter queue.
 * La meme topologie est declaree cote FastAPI (definitions identiques -> declaration idempotente).
 */
@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "supportiq.tickets";
    public static final String ROUTING_KEY_CREATED = "ticket.created";
    public static final String QUEUE_ANALYZE = "tickets.analyze";
    public static final String DLX = "supportiq.tickets.dlx";
    public static final String QUEUE_DLQ = "tickets.analyze.dlq";

    @Bean
    public TopicExchange ticketsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange ticketsDeadLetterExchange() {
        return new TopicExchange(DLX, true, false);
    }

    @Bean
    public Queue analyzeQueue() {
        return QueueBuilder.durable(QUEUE_ANALYZE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", ROUTING_KEY_CREATED)
                .build();
    }

    @Bean
    public Queue analyzeDeadLetterQueue() {
        return QueueBuilder.durable(QUEUE_DLQ).build();
    }

    @Bean
    public Binding analyzeBinding() {
        return BindingBuilder.bind(analyzeQueue()).to(ticketsExchange()).with(ROUTING_KEY_CREATED);
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(analyzeDeadLetterQueue()).to(ticketsDeadLetterExchange())
                .with(ROUTING_KEY_CREATED);
    }

    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        return template;
    }
}
