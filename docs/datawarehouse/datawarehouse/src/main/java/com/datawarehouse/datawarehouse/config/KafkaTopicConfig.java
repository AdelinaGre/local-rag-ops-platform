package com.datawarehouse.datawarehouse.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {
    @Bean
    public NewTopic ingestionRequestsTopic(
            @Value("${app.kafka.topics.ingestion-requests}") String topic
    ) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic rawMarketDataTopic( // for streaming
            @Value("${app.kafka.topics.marketdata-raw}") String topic
    ) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }

}
