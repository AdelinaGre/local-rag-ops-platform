package com.datawarehouse.datawarehouse.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Document(collection = "time_series_data")
public class TimeSeriesData {
    @Id
    private String documentId = UUID.randomUUID().toString();
    private String id;
    private String assetId;
    private String dataSourceId;
    private Instant businessDate;
    private Instant systemDate;
    private Map<String, Object> payload;
    private boolean deleted;
    private Integer businessYear;
    private String provider;
    private String dataset;
    private String payloadHash;
    private String sourceRecordKey;
    private String ingestionRunId;
    private Map<String,String> requestContext;

    public TimeSeriesData(
            String id,
            String assetId,
            String dataSourceId,
            Instant businessDate,
            Instant systemDate,
            Map<String, ?> payload,
            boolean deleted,
            Integer businessYear,
            String provider,
            String dataset,
            Map<String, String> requestContext
    ) {
        this(
                id,
                assetId,
                dataSourceId,
                businessDate,
                systemDate,
                payload == null ? null : new java.util.LinkedHashMap<>(payload),
                deleted,
                businessYear,
                provider,
                dataset,
                requestContext,
                null,
                null,
                null
        );
    }

    public TimeSeriesData(
            String id,
            String assetId,
            String dataSourceId,
            Instant businessDate,
            Instant systemDate,
            Map<String, Object> payload,
            boolean deleted,
            Integer businessYear,
            String provider,
            String dataset,
            Map<String, String> requestContext,
            String payloadHash,
            String sourceRecordKey,
            String ingestionRunId
    ) {
        this.documentId = UUID.randomUUID().toString();
        this.id = id;
        this.assetId = assetId;
        this.dataSourceId = dataSourceId;
        this.businessDate = businessDate;
        this.systemDate = systemDate;
        this.payload = payload;
        this.deleted = deleted;
        this.businessYear = businessYear;
        this.provider = provider;
        this.dataset = dataset;
        this.requestContext = requestContext;
        this.payloadHash = payloadHash;
        this.sourceRecordKey = sourceRecordKey;
        this.ingestionRunId = ingestionRunId;
    }


}
