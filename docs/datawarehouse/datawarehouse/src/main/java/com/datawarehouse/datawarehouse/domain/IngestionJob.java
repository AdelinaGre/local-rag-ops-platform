package com.datawarehouse.datawarehouse.domain;

import com.datawarehouse.datawarehouse.ingestion.model.IngestionResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection="ingestion_job")
public class IngestionJob {
    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";

    private String id;

    private String provider;
    private String assetId;
    private String status;

    private Instant requestedAt;
    private Instant startedAt;
    private Instant completedAt;

    private Integer fetchedRecords;
    private Integer transformedRecords;
    private Integer storedRecords;
    private Integer skippedRecords;
    private Integer failedRecords;

    private String message;
    private String error;

    public static IngestionJob pending(String id, String provider,String assetId){
        return IngestionJob.builder()
                .id(id)
                .provider(provider)
                .assetId(assetId)
                .status(PENDING)
                .requestedAt(Instant.now())
                .fetchedRecords(0)
                .transformedRecords(0)
                .storedRecords(0)
                .skippedRecords(0)
                .failedRecords(0)
                .build();
    }

    public void markRunning() {
        this.status = RUNNING;
        this.startedAt = Instant.now();
        this.error = null;
    }

    public void markCompleted(IngestionResult result) {
        this.status = COMPLETED;
        this.completedAt = Instant.now();
        this.fetchedRecords = result.getFetchedRecords();
        this.transformedRecords = result.getTransformedRecords();
        this.storedRecords = result.getStoredRecords();
        this.skippedRecords = result.getSkippedRecords();
        this.failedRecords = result.getFailedRecords();
        this.message = result.getMessage();
        this.error = null;
    }

    public void markFailed(Exception exception) {
        this.status = FAILED;
        this.completedAt = Instant.now();
        this.failedRecords = this.failedRecords == null ? 1 : this.failedRecords + 1;
        this.error = exception.getMessage();
    }
}
