package com.drawback.events;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * Every consumer in the platform gets the same failure policy: 4 retries with exponential backoff, then the
 * record is parked on "<topic>.DLT" so one poison message can never block a partition.
 * Boot wires a single {@link DefaultErrorHandler} bean into the default listener container factory.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
public class KafkaConsumerErrorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DefaultErrorHandler.class)
    DefaultErrorHandler kafkaErrorHandler(KafkaProperties kafkaProperties) {
        Map<String, Object> producerProps = kafkaProperties.buildProducerProperties(null);

        // A record that failed deserialisation reaches us as raw bytes; everything else is a typed object.
        // Order matters: the recoverer picks the first template whose key type matches.
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, new KafkaTemplate<>(
                new DefaultKafkaProducerFactory<>(producerProps, new StringSerializer(), new ByteArraySerializer())));
        templates.put(Object.class, new KafkaTemplate<>(
                new DefaultKafkaProducerFactory<>(producerProps, new StringSerializer(), new JsonSerializer<>())));

        // partition -1 = let the producer choose by key (the DLT may have a different partition count)
        var recoverer = new DeadLetterPublishingRecoverer(templates,
                (record, ex) -> new TopicPartition(record.topic() + Topics.DLT_SUFFIX, -1));

        var backOff = new ExponentialBackOffWithMaxRetries(4);
        backOff.setInitialInterval(500);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(8_000);

        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(DeserializationException.class, IllegalArgumentException.class);
        return handler;
    }
}
