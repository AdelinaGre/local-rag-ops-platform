package com.datawarehouse.datawarehouse.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "analytics_yearly_summaries")
public class AnalyticsYearlySummary {
    @Id
    private String documentId;
    private String assetId;
    private String dataSourceId;
    private Integer businessYear;
    private Long count;
    private Double minClose;
    private Double maxClose;
    private Double avgClose;
    private Double avgVolume;
    private Instant computedAt;
    private String jobName;
}
