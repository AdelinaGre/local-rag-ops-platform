package com.datawarehouse.datawarehouse.service;

import com.datawarehouse.datawarehouse.domain.Asset;
import com.datawarehouse.datawarehouse.domain.DataSource;
import com.datawarehouse.datawarehouse.web.dataTransferObject.AssistantChatRequest;
import com.datawarehouse.datawarehouse.web.dataTransferObject.AssistantChatResponse;
import com.datawarehouse.datawarehouse.web.dataTransferObject.AssistantToolCallResponse;
import com.datawarehouse.datawarehouse.web.dataTransferObject.PagedIdsResponse;
import com.datawarehouse.datawarehouse.web.dataTransferObject.TimeSeriesPointResponse;
import com.datawarehouse.datawarehouse.web.dataTransferObject.TimeSeriesQueryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AssistantAgentService {

    private static final int DISCOVERY_LIMIT = 100;
    private static final int SERIES_LIMIT = 100;
    private static final int MAX_SERIES_DAYS = 366;

    private final WarehouseReadService warehouseReadService;
    private final LlmChatClient llmChatClient;
    private final ObjectMapper objectMapper;

    public AssistantChatResponse chat(AssistantChatRequest request) {
        String message = normalizeMessage(request.message());
        LocalDate endDate = request.endBusinessDate() == null ? LocalDate.now(ZoneOffset.UTC) : request.endBusinessDate();
        LocalDate startDate = request.startBusinessDate() == null ? endDate.minusDays(30) : request.startBusinessDate();
        validateDateRange(startDate, endDate);

        boolean includeAttributes = request.includeAttributes() == null || request.includeAttributes();
        List<AssistantToolCallResponse> toolCalls = new ArrayList<>();

        PagedIdsResponse assetsPage = warehouseReadService.listAssets(0, DISCOVERY_LIMIT);
        toolCalls.add(toolCall(
                "list_assets",
                Map.of("offset", 0, "limit", DISCOVERY_LIMIT),
                "OK",
                "Discovered " + assetsPage.getItems().size() + " warehouse asset identifiers."
        ));

        PagedIdsResponse sourcesPage = warehouseReadService.listDataSources(0, DISCOVERY_LIMIT);
        toolCalls.add(toolCall(
                "list_data_sources",
                Map.of("offset", 0, "limit", DISCOVERY_LIMIT),
                "OK",
                "Discovered " + sourcesPage.getItems().size() + " warehouse data-source identifiers."
        ));

        String assetId = chooseAssetId(request.assetId(), message, assetsPage.getItems());
        String dataSourceId = chooseDataSourceId(request.dataSourceId(), assetId, message, sourcesPage.getItems());

        if (!StringUtils.hasText(assetId)) {
            return noSelectionResponse(message, toolCalls, assetsPage, sourcesPage);
        }

        Asset asset = warehouseReadService.getAsset(assetId);
        toolCalls.add(toolCall(
                "get_asset_details",
                Map.of("assetId", assetId),
                asset == null ? "EMPTY" : "OK",
                asset == null ? "No asset metadata found." : "Loaded latest visible asset metadata."
        ));

        DataSource dataSource = null;
        if (StringUtils.hasText(dataSourceId)) {
            dataSource = warehouseReadService.getDataSource(dataSourceId);
            toolCalls.add(toolCall(
                    "get_data_source_details",
                    Map.of("dataSourceId", dataSourceId),
                    dataSource == null ? "EMPTY" : "OK",
                    dataSource == null ? "No data-source metadata found." : "Loaded latest visible data-source metadata."
            ));
        }

        TimeSeriesQueryResponse series = null;
        if (StringUtils.hasText(dataSourceId)) {
            series = warehouseReadService.getTimeSeries(
                    assetId,
                    dataSourceId,
                    startDate.atStartOfDay().toInstant(ZoneOffset.UTC),
                    endDate.atStartOfDay().toInstant(ZoneOffset.UTC),
                    includeAttributes,
                    0,
                    SERIES_LIMIT
            );
            toolCalls.add(toolCall(
                    "get_time_series_data",
                    Map.of(
                            "assetId", assetId,
                            "dataSourceId", dataSourceId,
                            "startBusinessDate", startDate.toString(),
                            "endBusinessDate", endDate.toString(),
                            "includeAttributes", includeAttributes,
                            "offset", 0,
                            "limit", SERIES_LIMIT
                    ),
                    series.getData().isEmpty() ? "EMPTY" : "OK",
                    "Returned " + series.getData().size() + " time-series records."
            ));
        }

        Map<String, Object> context = buildContext(message, assetId, dataSourceId, startDate, endDate, asset, dataSource, series);
        String fallback = fallbackAnswer(context);
        Optional<String> llmAnswer = llmChatClient.complete(systemPrompt(), userPrompt(context));

        return new AssistantChatResponse(
                llmAnswer.orElse(fallback),
                llmAnswer.isPresent(),
                llmAnswer.isPresent() ? llmChatClient.getModel() : "deterministic-fallback",
                toolCalls,
                context
        );
    }

    private AssistantChatResponse noSelectionResponse(
            String message,
            List<AssistantToolCallResponse> toolCalls,
            PagedIdsResponse assetsPage,
            PagedIdsResponse sourcesPage
    ) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("userPrompt", message);
        context.put("availableAssets", assetsPage.getItems());
        context.put("availableDataSources", sourcesPage.getItems());
        context.put("status", "NO_ASSET_SELECTED");

        return new AssistantChatResponse(
                "I could not select a warehouse asset from your prompt. Choose an instrument in the UI or mention one of the listed asset identifiers.",
                false,
                "deterministic-fallback",
                toolCalls,
                context
        );
    }

    private Map<String, Object> buildContext(
            String message,
            String assetId,
            String dataSourceId,
            LocalDate startDate,
            LocalDate endDate,
            Asset asset,
            DataSource dataSource,
            TimeSeriesQueryResponse series
    ) {
        Map<String, Object> selection = new LinkedHashMap<>();
        selection.put("assetId", assetId);
        selection.put("dataSourceId", dataSourceId);
        selection.put("startBusinessDate", startDate);
        selection.put("endBusinessDate", endDate);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("userPrompt", message);
        context.put("selection", selection);
        context.put("asset", asset);
        context.put("dataSource", dataSource);
        context.put("timeSeriesSummary", summarizeSeries(series));
        context.put("temporalSemantics", Map.of(
                "businessDate", "valid date of the market value",
                "systemDate", "warehouse transaction/version time",
                "versionRule", "latest visible version returned by the warehouse read layer"
        ));
        context.put("provenance", Map.of(
                "source", "spring-boot-assistant-agent",
                "toolContract", "MCP-compatible warehouse read tools",
                "warehouseApi", "/api/v1"
        ));
        return context;
    }

    private Map<String, Object> summarizeSeries(TimeSeriesQueryResponse series) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (series == null || series.getData() == null || series.getData().isEmpty()) {
            summary.put("status", "EMPTY");
            summary.put("recordCount", 0);
            return summary;
        }

        List<TimeSeriesPointResponse> rows = series.getData().stream()
                .sorted(Comparator.comparing(TimeSeriesPointResponse::getBusinessDate))
                .toList();

        List<Double> closes = rows.stream()
                .map(row -> readClose(row.getPayload()))
                .flatMap(Optional::stream)
                .toList();

        summary.put("status", closes.isEmpty() ? "NO_CLOSE_VALUES" : "OK");
        summary.put("recordCount", rows.size());
        summary.put("firstBusinessDate", rows.getFirst().getBusinessDate());
        summary.put("lastBusinessDate", rows.getLast().getBusinessDate());

        if (!closes.isEmpty()) {
            double firstClose = closes.getFirst();
            double lastClose = closes.getLast();
            double minClose = closes.stream().mapToDouble(Double::doubleValue).min().orElse(firstClose);
            double maxClose = closes.stream().mapToDouble(Double::doubleValue).max().orElse(firstClose);

            summary.put("firstClose", firstClose);
            summary.put("lastClose", lastClose);
            summary.put("minClose", minClose);
            summary.put("maxClose", maxClose);
            summary.put("direction", lastClose > firstClose ? "UP" : lastClose < firstClose ? "DOWN" : "FLAT");
            summary.put("absoluteChange", lastClose - firstClose);
            summary.put("percentageChange", firstClose == 0 ? null : ((lastClose - firstClose) / firstClose) * 100.0);
        }

        return summary;
    }

    private Optional<Double> readClose(Map<String, Object> payload) {
        if (payload == null) {
            return Optional.empty();
        }

        for (String key : List.of("close", "last", "mid", "price", "Close", "Last")) {
            Object value = payload.get(key);
            if (value instanceof Number number) {
                return Optional.of(number.doubleValue());
            }
            if (value instanceof String text) {
                try {
                    return Optional.of(Double.parseDouble(text));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        return Optional.empty();
    }

    private String fallbackAnswer(Map<String, Object> context) {
        Map<?, ?> selection = (Map<?, ?>) context.get("selection");
        Map<?, ?> summary = (Map<?, ?>) context.get("timeSeriesSummary");

        if ("EMPTY".equals(summary.get("status"))) {
            return "I checked the warehouse using the MCP-style read workflow, but no time-series records were found for "
                    + selection.get("assetId") + " from " + selection.get("startBusinessDate")
                    + " to " + selection.get("endBusinessDate") + ".";
        }

        return "I used the warehouse read tools to inspect " + selection.get("assetId")
                + " from " + selection.get("startBusinessDate") + " to " + selection.get("endBusinessDate")
                + ". The selected interval contains " + summary.get("recordCount") + " records. "
                + "The close-price direction is " + valueOr(summary, "direction", "unknown")
                + ", from " + valueOr(summary, "firstClose", "n/a")
                + " to " + valueOr(summary, "lastClose", "n/a")
                + ". This is a data summary only, not financial advice.";
    }

    private Object valueOr(Map<?, ?> map, String key, Object fallback) {
        Object value = map.get(key);
        return value == null ? fallback : value;
    }

    private String systemPrompt() {
        return """
                You are an LLM assistant connected to a financial data warehouse through MCP-style read tools.
                Use only the provided JSON context. Do not invent data and do not make investment recommendations.
                Explain which tools were used at a high level, summarize the records, mention temporal semantics and provenance.
                Keep the response concise and useful for a data warehouse lab demonstration.
                """;
    }

    private String userPrompt(Map<String, Object> context) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(context);
        } catch (Exception exception) {
            return context.toString();
        }
    }

    private String chooseAssetId(String explicitAssetId, String message, List<String> assetIds) {
        if (StringUtils.hasText(explicitAssetId)) {
            return explicitAssetId.trim();
        }

        String normalizedMessage = message.toLowerCase();
        return assetIds.stream()
                .filter(id -> normalizedMessage.contains(id.toLowerCase()) || normalizedMessage.contains(lastSegment(id).toLowerCase()))
                .findFirst()
                .or(() -> assetIds.stream().filter(id -> normalizedMessage.contains("crypto") && id.toLowerCase().contains("binance")).findFirst())
                .or(() -> assetIds.stream().filter(id -> normalizedMessage.contains("crypto") && id.toLowerCase().contains("bitfinex")).findFirst())
                .or(() -> assetIds.stream().findFirst())
                .orElse("");
    }

    private String chooseDataSourceId(String explicitDataSourceId, String assetId, String message, List<String> dataSourceIds) {
        if (StringUtils.hasText(explicitDataSourceId)) {
            return explicitDataSourceId.trim();
        }

        String normalizedMessage = message.toLowerCase();
        Optional<String> fromPrompt = dataSourceIds.stream()
                .filter(id -> normalizedMessage.contains(id.toLowerCase()) || normalizedMessage.contains(lastSegment(id).toLowerCase()))
                .findFirst();
        if (fromPrompt.isPresent()) {
            return fromPrompt.get();
        }

        String inferred = inferDataSourceFromAsset(assetId);
        if (StringUtils.hasText(inferred) && dataSourceIds.contains(inferred)) {
            return inferred;
        }

        return dataSourceIds.stream()
                .filter(id -> StringUtils.hasText(assetId) && assetId.startsWith(id + "/"))
                .findFirst()
                .orElse("");
    }

    private String inferDataSourceFromAsset(String assetId) {
        if (!StringUtils.hasText(assetId) || !assetId.contains("/")) {
            return "";
        }
        int lastSlash = assetId.lastIndexOf('/');
        return lastSlash > 0 ? assetId.substring(0, lastSlash) : "";
    }

    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startBusinessDate must be before or equal to endBusinessDate.");
        }

        long days = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (days > MAX_SERIES_DAYS) {
            throw new IllegalArgumentException("Assistant time-series interval cannot exceed " + MAX_SERIES_DAYS + " days.");
        }
    }

    private String normalizeMessage(String message) {
        if (!StringUtils.hasText(message)) {
            throw new IllegalArgumentException("Assistant message is required.");
        }
        return message.trim();
    }

    private AssistantToolCallResponse toolCall(
            String name,
            Map<String, Object> arguments,
            String status,
            String summary
    ) {
        return new AssistantToolCallResponse(name, arguments, status, summary);
    }

    private String lastSegment(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String[] parts = value.split("/");
        return parts.length == 0 ? value : parts[parts.length - 1];
    }
}
