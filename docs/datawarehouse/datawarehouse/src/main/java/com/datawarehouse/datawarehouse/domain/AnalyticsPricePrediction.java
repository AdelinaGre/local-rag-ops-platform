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
@Document(collection = "analytics_price_predictions")
public class AnalyticsPricePrediction {
    @Id
    private String documentId;
    private String assetId;
    private String dataSourceId;
    private String date;
    private Double actualClose;
    private Double predictedClose;
    private Double residual;
    private Double modelR2;
    private Double modelRMSE;
    private Instant computedAt;
    private String jobName;
}
