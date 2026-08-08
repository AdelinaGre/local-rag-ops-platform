package com.datawarehouse.datawarehouse.ingestion.streaming;


import com.datawarehouse.datawarehouse.domain.IngestionJob;
import com.datawarehouse.datawarehouse.ingestion.MarketDataIngestionService;
import com.datawarehouse.datawarehouse.ingestion.model.IngestionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KafkaIngestionConsumer {
    private final MarketDataIngestionService marketDataIngestionService;
    private final MongoTemplate mongoTemplate;

    @KafkaListener(
            topics = "${app.kafka.topics.ingestion-requests}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void consume(IngestionCommand command) {
        IngestionJob job = mongoTemplate.findById(command.jobId(), IngestionJob.class);

        if (job == null) {
            job = IngestionJob.pending(command.jobId(), command.provider(), command.assetId());
            mongoTemplate.insert(job);
        }

        job.markRunning();
        mongoTemplate.save(job);

        try {
            IngestionResult result = marketDataInestion(command);
            job.markCompleted(result);
            mongoTemplate.save(job);
        } catch (Exception exception) {
            job.markFailed(exception);
            mongoTemplate.save(job);
        }
    }

    private IngestionResult marketDataInestion(IngestionCommand command) {
        return marketDataIngestionService.ingest(
                command.provider(),
                command.assetId()
        );
    }

}
