package com.datawarehouse.datawarehouse.ingestion.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "marketdata.alphavantage")
public class AlphaVantageApiProperties {
    private String baseUrl = "https://www.alphavantage.co";
    private String apiKey;
    private String outputSize = "compact";
}
