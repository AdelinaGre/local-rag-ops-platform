package com.datawarehouse.datawarehouse.ingestion.extractor;

import com.datawarehouse.datawarehouse.ingestion.config.MarketDataApiProperties;
import com.datawarehouse.datawarehouse.ingestion.MarketDataProviderException;
import com.datawarehouse.datawarehouse.ingestion.model.RawMarketDataPage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class NasdaqMarketDataExtractor implements MarketDataExtractor {

    private final RestClient restClient; //requesturi http
    private final MarketDataApiProperties properties; // ca saa ia config api : databaseCode, tableCode, key

    @Override
    public String providerId() {
        return "nasdaq";
    }

    @Override
    public RawMarketDataPage fetchFirstPage(String assetIdentifier) {
        ensureApiKey();

        JsonNode response = executeNasdaqRequest(() -> restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https")
                        .host("data.nasdaq.com")
                        .path("/api/v3/datatables/{databaseCode}/{tableCode}.json")
                        .queryParam("code", assetIdentifier)
                        .queryParam("api_key", properties.getKey())
                        .build(properties.getDatabaseCode(), properties.getTableCode()))
                .retrieve()
                .body(JsonNode.class));

        return toRawPage(response);
    }

    @Override
    public RawMarketDataPage fetchNextPage(String assetIdentifier, String nextCursor) {
        ensureApiKey();

        JsonNode response = executeNasdaqRequest(() -> restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https")
                        .host("data.nasdaq.com")
                        .path("/api/v3/datatables/{databaseCode}/{tableCode}.json")
                        .queryParam("code", assetIdentifier)
                        .queryParam("qopts.cursor_id", nextCursor)
                        .queryParam("api_key", properties.getKey())
                        .build(properties.getDatabaseCode(), properties.getTableCode()))
                .retrieve()
                .body(JsonNode.class)); // primeste raspuns json

        return toRawPage(response); // si apoi il transforma in RawMarketDataPage
    }

    private void ensureApiKey() {
        if (properties.getKey() == null || properties.getKey().isBlank()) {
            throw new MarketDataProviderException(
                    "Nasdaq Data Link API key is missing in the running Spring Boot process. "
                            + "Set NASDAQ_DATA_LINK_API_KEY before starting bootRun."
            );
        }
    }

    private JsonNode executeNasdaqRequest(NasdaqRequest request) {
        try {
            return request.execute();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().is4xxClientError()) {
                throw new MarketDataProviderException(
                        "Nasdaq Data Link rejected the request. Check NASDAQ_DATA_LINK_API_KEY and the requested asset code.",
                        ex
                );
            }
            throw new MarketDataProviderException("Nasdaq Data Link request failed: " + ex.getMessage(), ex);
        } catch (RestClientException ex) {
            throw new MarketDataProviderException("Nasdaq Data Link request failed: " + ex.getMessage(), ex);
        }
    }

    private RawMarketDataPage toRawPage(JsonNode response) {
        JsonNode datatable = response.path("datatable");
        JsonNode columns = datatable.path("columns");
        JsonNode data = datatable.path("data");

        List<Map<String, Object>> records = new ArrayList<>();

        for (JsonNode row : data) { // pentru fiecare rand din data citeste numele coloanelor din columns
            records.add(toRecord(columns, row)); // construieste un Map<String,Object> cu valorile reale
        }

        String nextCursor = null;
        JsonNode meta = response.path("meta");
        if (meta.has("next_cursor_id") && !meta.get("next_cursor_id").isNull()) {
            nextCursor = meta.get("next_cursor_id").textValue();
        }
        String datasetId = properties.getDatabaseCode() + "/" + properties.getTableCode();
        return new RawMarketDataPage( // la final returneaza
                records, // lista de recorduri brute
                nextCursor, // cursorul urmator
                nextCursor != null && !nextCursor.isBlank(),// daca mai exista pagini
                "NasdaqDataLink", // providerul
                datasetId // datasetul
        );
    }

    private Map<String, Object> toRecord(JsonNode columns, JsonNode row) {
        LinkedHashMap<String, Object> record = new LinkedHashMap<>();

        for (int i = 0; i < columns.size() && i < row.size(); i++) {
            JsonNode columnNode = columns.get(i);
            String columnName = columnNode.path("name").textValue();
            JsonNode valueNode = row.get(i);
            record.put(columnName, extractValue(valueNode));
        }

        return record;
    }

    private Object extractValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        return node.toString();
    }

    @FunctionalInterface
    private interface NasdaqRequest {
        JsonNode execute();
    }
}
