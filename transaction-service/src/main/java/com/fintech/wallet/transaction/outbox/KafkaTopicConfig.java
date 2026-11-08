package com.fintech.wallet.transaction.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    /** Created on startup if missing. Keyed by transfer id, 3 partitions let consumers scale out. */
    @Bean
    NewTopic transferEventsTopic(OutboxProperties properties) {
        return TopicBuilder.name(properties.topic()).partitions(3).replicas(1).build();
    }
}
