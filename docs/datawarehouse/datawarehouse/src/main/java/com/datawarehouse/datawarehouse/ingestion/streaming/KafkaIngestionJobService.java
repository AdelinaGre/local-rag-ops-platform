package com.datawarehouse.datawarehouse.ingestion.streaming;

import com.datawarehouse.datawarehouse.domain.IngestionJob;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KafkaIngestionJobService {

    private final KafkaTemplate<String, IngestionCommand> kafkaTemplate;
    private final MongoTemplate mongoTemplate;

    @Value("${app.kafka.topics.ingestion-requests}")
    private String ingestionRequestsTopic;

    public IngestionJob submit(String provider, String assetId) {
        String jobId = UUID.randomUUID().toString();

        IngestionJob job = IngestionJob.pending(jobId, provider, assetId);
        mongoTemplate.insert(job);

        IngestionCommand command = new IngestionCommand(
                jobId,
                provider,
                assetId,
                Instant.now()
        );

        kafkaTemplate.send(ingestionRequestsTopic, jobId, command);
        return job;
    }
    public IngestionJob findById(String jobId) {
        return mongoTemplate.findById(jobId, IngestionJob.class);
    }

    public List<IngestionJob> findRecent(int limit) {
        Query query = new Query()
                .with(Sort.by(Sort.Direction.DESC, "requestedAt"))
                .limit(Math.max(1, Math.min(limit, 100)));

        return mongoTemplate.find(query, IngestionJob.class);
    }
}
