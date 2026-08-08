package com.datawarehouse.datawarehouse.ingestion.streaming;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor

public class KafkaRawMarketDataProducer {
    private final KafkaTemplate<String, RawMarketDataEvent> kafkaTemplate;

    @Value("${app.kafka.topics.marketdata-raw}")
    private String rawTopic;

    public void publish(RawMarketDataEvent event) {
        kafkaTemplate.send(rawTopic, event.assetId(), event);
    }
}
