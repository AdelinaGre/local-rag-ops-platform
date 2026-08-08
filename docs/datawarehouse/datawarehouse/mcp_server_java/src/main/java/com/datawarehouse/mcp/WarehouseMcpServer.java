package com.datawarehouse.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServerFeatures;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class WarehouseMcpServer {

    private static final String API_BASE = normalizeApiBase(System.getenv().getOrDefault(
            "WAREHOUSE_API_BASE",
            "http://127.0.0.1:8081/api/v1"
    ));

    private static final int DEFAULT_PAGE_LIMIT = 20;
    private static final int MAX_PAGE_LIMIT = 100;
    private static final int DEFAULT_SERIES_LIMIT = 100;
    private static final int MAX_SERIES_LIMIT = 500;
    private static final int MAX_SERIES_DAYS = 366;

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z0-9._:/-]+$");

    private static final McpJsonMapper MCP_JSON = new JacksonMcpJsonMapperSupplier().get();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public static void main(String[] args) {
        McpServerTransportProvider transportProvider = new StdioServerTransportProvider(MCP_JSON);

        var server = McpServer.sync(transportProvider)
                .serverInfo("financial-market-dwh", "1.0.0")
                .instructions("""
                This MCP server exposes read-only financial data warehouse tools.
                Use list_assets first to discover instruments, then inspect the selected asset and data source,
                then call get_time_series_data with a bounded business-date interval.
                Do not provide financial advice. Summarize only the returned warehouse data and mention provenance.
                """)
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(false)
                        .prompts(false)
                        .resources(false, false)
                        .build())
                .toolCall(tool(
                        "list_assets",
                        "List Financial Assets",
                        "Returns a paginated list of financial asset identifiers available in the data warehouse.",
                        """
                        {
                          "type": "object",
                          "properties": {
                            "offset": { "type": "integer", "minimum": 0, "default": 0 },
                            "limit": { "type": "integer", "minimum": 1, "maximum": 100, "default": 20 }
                          }
                        }
                        """
                ), (exchange, request) -> result(() -> listAssets(request.arguments())))
                .toolCall(tool(
                        "get_asset_details",
                        "Get Asset Details",
                        "Returns the latest visible details for one financial asset identifier.",
                        """
                        {
                          "type": "object",
                          "properties": {
                            "assetId": { "type": "string" }
                          },
                          "required": ["assetId"]
                        }
                        """
                ), (exchange, request) -> result(() -> getAssetDetails(request.arguments())))
                .toolCall(tool(
                        "list_data_sources",
                        "List Data Sources",
                        "Returns a paginated list of financial data-source identifiers available in the data warehouse.",
                        """
                        {
                          "type": "object",
                          "properties": {
                            "offset": { "type": "integer", "minimum": 0, "default": 0 },
                            "limit": { "type": "integer", "minimum": 1, "maximum": 100, "default": 20 }
                          }
                        }
                        """
                ), (exchange, request) -> result(() -> listDataSources(request.arguments())))
                .toolCall(tool(
                        "get_data_source_details",
                        "Get Data Source Details",
                        "Returns details about one financial data source, including provider, dataset, request context, and attributes.",
                        """
                        {
                          "type": "object",
                          "properties": {
                            "dataSourceId": { "type": "string" }
                          },
                          "required": ["dataSourceId"]
                        }
                        """
                ), (exchange, request) -> result(() -> getDataSourceDetails(request.arguments())))
                .toolCall(tool(
                        "get_time_series_data",
                        "Get Time-Series Data",
                        "Returns bounded time-series records for one asset and data source. Dates must use YYYY-MM-DD. Maximum interval is 366 days.",
                        """
                        {
                          "type": "object",
                          "properties": {
                            "assetId": { "type": "string" },
                            "dataSourceId": { "type": "string" },
                            "startBusinessDate": { "type": "string" },
                            "endBusinessDate": { "type": "string" },
                            "includeAttributes": { "type": "boolean", "default": false },
                            "offset": { "type": "integer", "minimum": 0, "default": 0 },
                            "limit": { "type": "integer", "minimum": 1, "maximum": 500, "default": 100 }
                          },
                          "required": ["assetId", "dataSourceId", "startBusinessDate", "endBusinessDate"]
                        }
                        """
                ), (exchange, request) -> result(() -> getTimeSeriesData(request.arguments())))
                .prompts(agenticTrendPrompt())
                .resources(warehouseToolsGuideResource())
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    }

    private static McpSchema.Tool tool(
            String name,
            String title,
            String description,
            String inputSchema
    ) {
        return McpSchema.Tool.builder()
                .name(name)
                .title(title)
                .description(description)
                .inputSchema(MCP_JSON, inputSchema)
                .build();
    }

    private static Map<String, Object> listAssets(Map<String, Object> args) throws Exception {
        int offset = validateOffset(readInt(args, "offset", 0));
        int limit = validateLimit(readInt(args, "limit", DEFAULT_PAGE_LIMIT), MAX_PAGE_LIMIT);

        Map<String, Object> response = getJson("/assets", Map.of(
                "offset", offset,
                "limit", limit
        ));

        return pageResponse(response, offset, limit, "/api/v1/assets");
    }

    private static Map<String, Object> getAssetDetails(Map<String, Object> args) throws Exception {
        String assetId = validateIdentifier(readString(args, "assetId"), "assetId");
        Map<String, Object> asset = getJson("/assets/" + quotePath(assetId), Map.of());

        Map<String, Object> temporal = new LinkedHashMap<>();
        temporal.put("view", "latest-visible-version");
        temporal.put("systemDate", asset.get("systemDate"));

        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("source", "warehouse-rest-api");
        provenance.put("endpoint", "/api/v1/assets/{assetId}");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("asset", asset);
        result.put("temporalSemantics", temporal);
        result.put("provenance", provenance);
        return result;
    }

    private static Map<String, Object> listDataSources(Map<String, Object> args) throws Exception {
        int offset = validateOffset(readInt(args, "offset", 0));
        int limit = validateLimit(readInt(args, "limit", DEFAULT_PAGE_LIMIT), MAX_PAGE_LIMIT);

        Map<String, Object> response = getJson("/data-sources", Map.of(
                "offset", offset,
                "limit", limit
        ));

        return pageResponse(response, offset, limit, "/api/v1/data-sources");
    }

    private static Map<String, Object> getDataSourceDetails(Map<String, Object> args) throws Exception {
        String dataSourceId = validateIdentifier(readString(args, "dataSourceId"), "dataSourceId");
        Map<String, Object> dataSource = getJson("/data-sources/" + quotePath(dataSourceId), Map.of());

        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("source", "warehouse-rest-api");
        provenance.put("endpoint", "/api/v1/data-sources/{dataSourceId}");
        provenance.put("provider", dataSource.get("provider"));
        provenance.put("dataset", dataSource.get("dataset"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dataSource", dataSource);
        result.put("provenance", provenance);
        return result;
    }

    private static Map<String, Object> getTimeSeriesData(Map<String, Object> args) throws Exception {
        String assetId = validateIdentifier(readString(args, "assetId"), "assetId");
        String dataSourceId = validateIdentifier(readString(args, "dataSourceId"), "dataSourceId");

        String startBusinessDate = readString(args, "startBusinessDate");
        String endBusinessDate = readString(args, "endBusinessDate");

        LocalDate startDate = validateDate(startBusinessDate, "startBusinessDate");
        LocalDate endDate = validateDate(endBusinessDate, "endBusinessDate");
        validateDateRange(startDate, endDate);

        boolean includeAttributes = readBoolean(args, "includeAttributes", false);
        int offset = validateOffset(readInt(args, "offset", 0));
        int limit = validateLimit(readInt(args, "limit", DEFAULT_SERIES_LIMIT), MAX_SERIES_LIMIT);

        Map<String, Object> response = getJson("/data", Map.of(
                "assetId", assetId,
                "dataSourceId", dataSourceId,
                "startBusinessDate", startBusinessDate,
                "endBusinessDate", endBusinessDate,
                "includeAttributes", includeAttributes,
                "offset", offset,
                "limit", limit
        ));

        Map<String, Object> selection = new LinkedHashMap<>();
        selection.put("assetId", assetId);
        selection.put("dataSourceId", dataSourceId);
        selection.put("startBusinessDate", startBusinessDate);
        selection.put("endBusinessDate", endBusinessDate);
        selection.put("includeAttributes", includeAttributes);
        selection.put("offset", offset);
        selection.put("limit", limit);

        Map<String, Object> page = new LinkedHashMap<>();
        page.put("offset", response.getOrDefault("offset", offset));
        page.put("limit", response.getOrDefault("limit", limit));
        page.put("hasNext", response.getOrDefault("hasNext", false));

        Map<String, Object> temporal = new LinkedHashMap<>();
        temporal.put("businessDate", "valid date of the market value");
        temporal.put("systemDate", "warehouse transaction/version time");
        temporal.put("versionRule", "latest visible version returned by the warehouse REST API");
        temporal.put("deletedRecords", "deletion markers are handled by the warehouse read layer");

        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("source", "warehouse-rest-api");
        provenance.put("endpoint", "/api/v1/data");
        provenance.put("assetId", assetId);
        provenance.put("dataSourceId", dataSourceId);

        Object records = response.getOrDefault("data", response.getOrDefault("items", List.of()));
        boolean empty = records instanceof List<?> list && list.isEmpty();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", empty ? "EMPTY" : "OK");
        result.put("message", empty
                ? "No records found for the selected asset, data source, and business-date interval."
                : "Records returned successfully.");
        result.put("selection", selection);
        result.put("records", records);
        result.put("attributes", response.getOrDefault("attributes", List.of()));
        result.put("page", page);
        result.put("temporalSemantics", temporal);
        result.put("provenance", provenance);
        return result;
    }

    private static Map<String, Object> pageResponse(
            Map<String, Object> response,
            int offset,
            int limit,
            String endpoint
    ) {
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("source", "warehouse-rest-api");
        provenance.put("endpoint", endpoint);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", response.getOrDefault("items", List.of()));
        result.put("offset", response.getOrDefault("offset", offset));
        result.put("limit", response.getOrDefault("limit", limit));
        result.put("hasNext", response.getOrDefault("hasNext", false));
        result.put("provenance", provenance);
        return result;
    }

    private static Map<String, Object> getJson(String path, Map<String, Object> params) throws Exception {
        URI uri = URI.create(API_BASE + path + buildQuery(params));

        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new McpToolException(
                    "WAREHOUSE_API_ERROR",
                    "Warehouse API returned HTTP " + response.statusCode() + ": " + response.body()
            );
        }

        if (response.body() == null || response.body().isBlank()) {
            return Map.of();
        }

        return JSON.readValue(response.body(), new TypeReference<>() {});
    }

    private static String buildQuery(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }

        StringBuilder query = new StringBuilder("?");
        boolean first = true;

        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (!first) {
                query.append("&");
            }

            first = false;
            query.append(encode(entry.getKey()));
            query.append("=");
            query.append(encode(String.valueOf(entry.getValue())));
        }

        return query.toString();
    }

    private static CallToolResult result(ToolBody body) {
        try {
            Map<String, Object> payload = body.execute();
            String json = MCP_JSON.writeValueAsString(payload);

            return CallToolResult.builder()
                    .addTextContent(json)
                    .structuredContent(payload)
                    .isError(false)
                    .build();
        } catch (McpToolException exception) {
            return error(exception.code, exception.getMessage());
        } catch (Exception exception) {
            return error("TOOL_EXECUTION_ERROR", exception.getMessage());
        }
    }

    private static CallToolResult error(String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", Map.of(
                "code", code,
                "message", message == null ? "Unexpected error" : message
        ));

        try {
            return CallToolResult.builder()
                    .addTextContent(MCP_JSON.writeValueAsString(payload))
                    .structuredContent(payload)
                    .isError(true)
                    .build();
        } catch (Exception exception) {
            return CallToolResult.builder()
                    .addTextContent("{\"error\":{\"code\":\"SERIALIZATION_ERROR\"}}")
                    .isError(true)
                    .build();
        }
    }

    private static int validateOffset(int value) {
        if (value < 0) {
            throw new McpToolException("INVALID_OFFSET", "offset must be greater than or equal to 0.");
        }

        return value;
    }

    private static int validateLimit(int value, int maxLimit) {
        if (value < 1 || value > maxLimit) {
            throw new McpToolException("INVALID_LIMIT", "limit must be between 1 and " + maxLimit + ".");
        }

        return value;
    }

    private static String validateIdentifier(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new McpToolException("MISSING_IDENTIFIER", fieldName + " is required.");
        }

        String normalized = value.trim();

        if (normalized.length() > 160) {
            throw new McpToolException("INVALID_IDENTIFIER", fieldName + " is too long.");
        }

        if (!IDENTIFIER_PATTERN.matcher(normalized).matches()) {
            throw new McpToolException(
                    "INVALID_IDENTIFIER",
                    fieldName + " may contain only letters, digits, dot, underscore, colon, slash, and dash."
            );
        }

        return normalized;
    }

    private static LocalDate validateDate(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new McpToolException("MISSING_DATE", fieldName + " is required.");
        }

        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new McpToolException("INVALID_DATE", fieldName + " must use YYYY-MM-DD format.");
        }
    }

    private static void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            throw new McpToolException(
                    "INVALID_DATE_RANGE",
                    "startBusinessDate must be before or equal to endBusinessDate."
            );
        }

        long days = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (days > MAX_SERIES_DAYS) {
            throw new McpToolException(
                    "DATE_RANGE_TOO_BROAD",
                    "Maximum accepted interval is " + MAX_SERIES_DAYS + " days."
            );
        }
    }

    private static int readInt(Map<String, Object> args, String key, int defaultValue) {
        Object value = args == null ? null : args.get(key);

        if (value == null) {
            return defaultValue;
        }

        if (value instanceof Number number) {
            double asDouble = number.doubleValue();
            if (asDouble % 1 != 0) {
                throw new McpToolException("INVALID_ARGUMENT", key + " must be an integer.");
            }
            return number.intValue();
        }

        throw new McpToolException("INVALID_ARGUMENT", key + " must be an integer.");
    }

    private static boolean readBoolean(Map<String, Object> args, String key, boolean defaultValue) {
        Object value = args == null ? null : args.get(key);

        if (value == null) {
            return defaultValue;
        }

        if (value instanceof Boolean bool) {
            return bool;
        }

        throw new McpToolException("INVALID_ARGUMENT", key + " must be a boolean.");
    }

    private static String readString(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);

        if (value instanceof String stringValue) {
            return stringValue;
        }

        throw new McpToolException("INVALID_ARGUMENT", key + " must be a string.");
    }

    private static String quotePath(String value) {
        String[] parts = value.split("/");
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                result.append("/");
            }

            result.append(encode(parts[i]));
        }

        return result.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String normalizeApiBase(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }

        return value;
    }

    @FunctionalInterface
    private interface ToolBody {
        Map<String, Object> execute() throws Exception;
    }

    private static class McpToolException extends RuntimeException {
        private final String code;

        private McpToolException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static McpServerFeatures.SyncPromptSpecification agenticTrendPrompt() {
        McpSchema.Prompt prompt = new McpSchema.Prompt(
                "warehouse_trend_analysis",
                "Warehouse Trend Analysis",
                "Guides an LLM client through a multi-step warehouse analysis using the read-only MCP tools.",
                List.of(
                        new McpSchema.PromptArgument(
                                "assetId",
                                "Asset Identifier",
                                "Selected warehouse asset identifier, for example BINANCE/SPOT/BTCUSDT.",
                                true
                        ),
                        new McpSchema.PromptArgument(
                                "dataSourceId",
                                "Data Source Identifier",
                                "Selected warehouse data source identifier, for example BINANCE/SPOT.",
                                true
                        ),
                        new McpSchema.PromptArgument(
                                "startBusinessDate",
                                "Start Business Date",
                                "Start date in YYYY-MM-DD format.",
                                true
                        ),
                        new McpSchema.PromptArgument(
                                "endBusinessDate",
                                "End Business Date",
                                "End date in YYYY-MM-DD format.",
                                true
                        )
                )
        );

        return new McpServerFeatures.SyncPromptSpecification(prompt, (exchange, request) -> {
            Map<String, Object> args = request.arguments();

            String assetId = validateIdentifier(readString(args, "assetId"), "assetId");
            String dataSourceId = validateIdentifier(readString(args, "dataSourceId"), "dataSourceId");

            String startBusinessDate = readString(args, "startBusinessDate");
            String endBusinessDate = readString(args, "endBusinessDate");

            LocalDate startDate = validateDate(startBusinessDate, "startBusinessDate");
            LocalDate endDate = validateDate(endBusinessDate, "endBusinessDate");
            validateDateRange(startDate, endDate);

            String text = """
                Analyze the selected warehouse asset using only MCP tool results.

                Required tool sequence:
                1. Call get_asset_details for assetId: %s
                2. Call get_data_source_details for dataSourceId: %s
                3. Call get_time_series_data with:
                   - assetId: %s
                   - dataSourceId: %s
                   - startBusinessDate: %s
                   - endBusinessDate: %s
                   - includeAttributes: true
                   - limit: 100

                After the tool calls, summarize:
                - available asset and source context
                - number of returned records
                - first and last business dates
                - observed close-price direction
                - min/max close if visible in returned records
                - provenance and temporal semantics

                Do not make investment recommendations.
                If records are empty, explain that no warehouse records exist for the selected interval.
                """.formatted(
                    assetId,
                    dataSourceId,
                    assetId,
                    dataSourceId,
                    startBusinessDate,
                    endBusinessDate
            );

            return new McpSchema.GetPromptResult(
                    "Multi-step MCP prompt for warehouse trend analysis.",
                    List.of(new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            new McpSchema.TextContent(text)
                    ))
            );
        });
    }

    private static McpServerFeatures.SyncResourceSpecification warehouseToolsGuideResource() {
        String uri = "warehouse://mcp/tools-guide";

        McpSchema.Resource resource = McpSchema.Resource.builder()
                .uri(uri)
                .name("warehouse_tools_guide")
                .title("Warehouse MCP Tools Guide")
                .description("Explains the read-only MCP tools and the recommended agentic workflow.")
                .mimeType("text/markdown")
                .build();

        return new McpServerFeatures.SyncResourceSpecification(resource, (exchange, request) -> {
            String text = """
                # Warehouse MCP Tools Guide

                This MCP server exposes the financial data warehouse consumption API as read-only tools.

                ## Recommended Agentic Workflow

                1. Call `list_assets` to discover available instruments.
                2. Call `get_asset_details` for the selected `assetId`.
                3. Call `list_data_sources` or `get_data_source_details` to inspect available providers and datasets.
                4. Call `get_time_series_data` for a bounded business-date interval.
                5. Let the LLM client summarize the returned records.

                ## Constraints

                - The server is read-only.
                - List operations use `offset` and `limit`.
                - Time-series reads require `assetId`, `dataSourceId`, `startBusinessDate`, and `endBusinessDate`.
                - Dates must use `YYYY-MM-DD`.
                - Maximum time-series interval is 366 days.
                - Maximum time-series page size is 500 records.

                ## Temporal Semantics

                - `businessDate` is the valid date of the market value.
                - `systemDate` is the warehouse transaction/version time.
                - Results follow the latest visible version rule from the warehouse REST API.
                - Deletion markers are handled by the warehouse read layer.

                ## Provenance

                Responses include provenance fields such as source endpoint, asset identifier, and data-source identifier.
                """;

            return new McpSchema.ReadResourceResult(List.of(
                    new McpSchema.TextResourceContents(uri, "text/markdown", text)
            ));
        });
    }
}
