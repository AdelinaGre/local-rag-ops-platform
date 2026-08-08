# Warehouse MCP Server

This module exposes the data warehouse consumption API as read-only Model Context Protocol tools.

## Purpose

The MCP server is an integration boundary between LLM clients and the warehouse. It does not access MongoDB directly. It calls the Spring Boot REST API under `/api/v1`.

## Tools

- `list_assets`
- `get_asset_details`
- `list_data_sources`
- `get_data_source_details`
- `get_time_series_data`

The server preserves pagination, bounded time-series reads, temporal semantics, and provenance metadata.

## Build

```powershell
cd C:\Users\adelg\Downloads\datawarehouse\datawarehouse
.\gradlew.bat -p mcp_server_java clean build
.\gradlew.bat -p mcp_server_java installDist
```

## MCP Inspector

Use this configuration:

```text
Transport Type: STDIO
Command: C:\Users\adelg\Downloads\datawarehouse\datawarehouse\mcp_server_java\build\install\mcp_server_java\bin\mcp_server_java.bat
Arguments: empty
```

Environment:

```text
WAREHOUSE_API_BASE=http://127.0.0.1:8081/api/v1
JAVA_HOME=C:\Users\adelg\.jdks\ms-21.0.10
```

## Example Tool Call

```json
{
  "assetId": "BINANCE/SPOT/BTCUSDT",
  "dataSourceId": "BINANCE/SPOT",
  "startBusinessDate": "2026-05-01",
  "endBusinessDate": "2026-05-31",
  "includeAttributes": true,
  "offset": 0,
  "limit": 20
}
```
