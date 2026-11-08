package com.fintech.wallet.notification.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    @Bean
    NewTopic transferEventsTopic(@Value("${app.kafka.topic}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }

    /** Same partition count as the main topic: a dead letter is published to the same partition number. */
    @Bean
    NewTopic transferEventsDeadLetterTopic(@Value("${app.kafka.topic}") String topic) {
        return TopicBuilder.name(topic + ".DLT").partitions(3).replicas(1).build();
    }

    /**
     * What happens when processing a message throws:
     * <ul>
     *   <li>a malformed message is useless forever, so it goes to the dead-letter topic at once;</li>
     *   <li>any other error (database briefly down, ...) is retried twice, 500 ms apart, then also dead-lettered.</li>
     * </ul>
     * Either way the consumer moves on, so one bad message can never block everything behind it.
     */
    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<String, String> template,
                                     @Value("${app.kafka.topic}") String topic) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(topic + ".DLT", record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(500L, 2));
        handler.addNotRetryableExceptions(MalformedEventException.class);
        return handler;
    }
}
