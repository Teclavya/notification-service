package com.teclavya.notification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConditionalOnProperty(name = "spring.rabbitmq.enabled", havingValue = "true", matchIfMissing = true)
public class RabbitMQConfig {

    public static final String EXCHANGE_NAME = "teclavya.events";
    public static final String QUEUE_NAME = "notification.support-ticket-events";
    public static final String DLX_EXCHANGE_NAME = "teclavya.events.dlx";
    public static final String DLQ_NAME = "notification.support-ticket-events.dlq";
    public static final String ROUTING_KEY_PATTERN = "ticket.#";

    @Bean
    public TopicExchange eventsExchange() {
        return new TopicExchange(EXCHANGE_NAME, true, false);
    }

    @Bean
    public TopicExchange deadLetterExchange() {
        return new TopicExchange(DLX_EXCHANGE_NAME, true, false);
    }

    @Bean
    public Queue supportTicketEventsQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DLX_EXCHANGE_NAME);
        args.put("x-dead-letter-routing-key", DLQ_NAME);
        return new Queue(QUEUE_NAME, true, false, false, args);
    }

    @Bean
    public Queue supportTicketEventsDlq() {
        return new Queue(DLQ_NAME, true, false, false);
    }

    @Bean
    public Binding supportTicketEventsBinding(Queue supportTicketEventsQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(supportTicketEventsQueue).to(eventsExchange).with(ROUTING_KEY_PATTERN);
    }

    @Bean
    public Binding supportTicketEventsDlqBinding(Queue supportTicketEventsDlq, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(supportTicketEventsDlq).to(deadLetterExchange).with("#");
    }

    @Bean
    public MessageConverter messageConverter() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new Jackson2JsonMessageConverter(mapper);
    }

    @Bean
    public org.springframework.amqp.rabbit.retry.MessageRecoverer messageRecoverer() {
        return new org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter,
            org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer configurer) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(messageConverter);
        return factory;
    }
}
