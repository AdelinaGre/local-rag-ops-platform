package com.datawarehouse.datawarehouse.config;

import com.datawarehouse.datawarehouse.ingestion.streaming.IngestionCommand;
import com.datawarehouse.datawarehouse.ingestion.streaming.RawMarketDataEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, IngestionCommand> ingestionCommandProducerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers
    ) {
        return new DefaultKafkaProducerFactory<>(producerConfig(bootstrapServers));
    }

    @Bean
    public KafkaTemplate<String, IngestionCommand> ingestionCommandKafkaTemplate(
            ProducerFactory<String, IngestionCommand> ingestionCommandProducerFactory
    ) {
        return new KafkaTemplate<>(ingestionCommandProducerFactory);
    }

    @Bean
    public ProducerFactory<String, RawMarketDataEvent> rawMarketDataProducerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers
    ) {
        return new DefaultKafkaProducerFactory<>(producerConfig(bootstrapServers));
    }

    @Bean
    public KafkaTemplate<String, RawMarketDataEvent> rawMarketDataKafkaTemplate(
            ProducerFactory<String, RawMarketDataEvent> rawMarketDataProducerFactory
    ) {
        return new KafkaTemplate<>(rawMarketDataProducerFactory);
    }

    private Map<String, Object> producerConfig(String bootstrapServers) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
        return config;
    }
}
