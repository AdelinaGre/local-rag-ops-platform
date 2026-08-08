package com.datawarehouse.datawarehouse.ingestion.extractor;

import com.datawarehouse.datawarehouse.ingestion.config.AlphaVantageApiProperties;
import com.datawarehouse.datawarehouse.ingestion.MarketDataProviderException;
import com.datawarehouse.datawarehouse.ingestion.model.RawMarketDataPage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AlphaVantageMarketDataExtractor implements MarketDataExtractor {

    private static final String TIME_SERIES_DAILY = "Time Series (Daily)";

    private final RestClient restClient;
    private final AlphaVantageApiProperties properties;

    @Override
    public String providerId() {
        return "alphavantage";
    }

    @Override
    public RawMarketDataPage fetchFirstPage(String assetIdentifier) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new MarketDataProviderException(
                    "Alpha Vantage API key is missing in the running Spring Boot process. "
                            + "Set ALPHAVANTAGE_API_KEY before starting bootRun."
            );
        }

        JsonNode response;
        try {
            response = restClient.get()
                    .uri(UriComponentsBuilder.fromUriString(properties.getBaseUrl())
                            .path("/query")
                            .queryParam("function", "TIME_SERIES_DAILY")
                            .queryParam("symbol", assetIdentifier)
                            .queryParam("outputsize", properties.getOutputSize())
                            .queryParam("apikey", properties.getApiKey())
                            .build()
                            .toUri())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException ex) {
            throw new MarketDataProviderException("Alpha Vantage request failed: " + ex.getMessage(), ex);
        }

        return toRawPage(assetIdentifier, response);
    }

    @Override
    public RawMarketDataPage fetchNextPage(String assetIdentifier, String nextCursor) {
        return null;
    }

    private RawMarketDataPage toRawPage(String assetIdentifier, JsonNode response) {
        if (response == null || response.isMissingNode() || response.isNull()) {
            throw new MarketDataProviderException("Alpha Vantage returned an empty response");
        }

        if (response.has("Error Message")) {
            throw new IllegalArgumentException(response.path("Error Message").asString());
        }

        if (response.has("Note")) {
            throw new MarketDataProviderException(response.path("Note").asString());
        }

        if (response.has("Information")) {
            throw new MarketDataProviderException(response.path("Information").asString());
        }

        JsonNode timeSeries = response.path(TIME_SERIES_DAILY);
        if (timeSeries.isMissingNode() || !timeSeries.isObject()) {
            throw new MarketDataProviderException("Alpha Vantage response does not contain " + TIME_SERIES_DAILY);
        }

        List<Map<String, Object>> records = new ArrayList<>();
        for (Map.Entry<String, JsonNode> field : timeSeries.properties()) {
            records.add(toRecord(assetIdentifier, field.getKey(), field.getValue()));
        }

        return new RawMarketDataPage(
                records,
                null,
                false,
                "AlphaVantage",
                "ALPHAVANTAGE/TIME_SERIES_DAILY"
        );
    }

    private Map<String, Object> toRecord(String assetIdentifier, String businessDate, JsonNode values) {
        LinkedHashMap<String, Object> record = new LinkedHashMap<>();
        record.put("code", assetIdentifier);
        record.put("date", businessDate);
        record.put("open", readDecimal(values, "1. open"));
        record.put("high", readDecimal(values, "2. high"));
        record.put("low", readDecimal(values, "3. low"));
        record.put("close", readDecimal(values, "4. close"));
        record.put("volume", readLong(values, "5. volume"));
        return record;
    }

    private Double readDecimal(JsonNode values, String fieldName) {
        JsonNode value = values.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return Double.valueOf(value.asString());
    }

    private Long readLong(JsonNode values, String fieldName) {
        JsonNode value = values.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return Long.valueOf(value.asString());
    }
}
