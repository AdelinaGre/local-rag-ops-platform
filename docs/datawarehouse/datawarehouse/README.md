# Financial Market Data Warehouse

Financial Market Data Warehouse is a Spring Boot, MongoDB, Kafka, Apache Spark, MCP, and LLM assistant project for ingesting, storing, analyzing, and querying financial market time-series data.

The project focuses on market data from multiple providers:

- Nasdaq Data Link historical market data
- Alpha Vantage daily stock data
- Binance WebSocket streaming crypto market data

The application exposes a browser UI, REST API, asynchronous ingestion workflow, Spark analytics jobs, MCP tools, and an LLM assistant interface.

## Architecture

```text
External Data Providers
  |-- Nasdaq Data Link REST
  |-- Alpha Vantage REST
  |-- Binance WebSocket
          |
          v
Spring Boot Data Warehouse API
  |-- ingestion services
  |-- Kafka producer/consumer services
  |-- warehouse read/write services
  |-- analytics run endpoints
  |-- LLM assistant endpoint
          |
          v
MongoDB
  |-- assets
  |-- data_sources
  |-- time_series_data
  |-- ingestion_job
  |-- analytics_yearly_summaries
  |-- analytics_price_predictions

Apache Spark / PySpark
  |-- compute_yearly_summaries.py
  |-- train_price_regression.py
          |
          v
MongoDB analytics collections

MCP Server
  |-- read-only tools over the existing REST consumption API
  |-- prompts and resources for agentic workflows
```

## Main Features

- REST ingestion from Nasdaq Data Link and Alpha Vantage.
- Kafka-based asynchronous ingestion request flow.
- Binance streaming integration through WebSocket events published to Kafka.
- Bitemporal-style warehouse records with `businessDate` and `systemDate`.
- Soft deletion support through deletion markers and latest-visible read logic.
- Read API for assets, data sources, and bounded time-series ranges.
- NDJSON streaming endpoint for time-series reads.
- Spark aggregation for yearly OHLCV summaries.
- Spark ML regression for price prediction output.
- React UI for dashboard, instruments, ingestion, streaming, analytics, and assistant flows.
- Java MCP server exposing warehouse read tools.
- LLM assistant UI backed by Spring Boot and Gemini/OpenAI-compatible chat completion API.

## Technology Stack

- Java 21
- Spring Boot 4
- Spring Data MongoDB
- Spring Kafka
- Apache Kafka
- MongoDB
- React + Vite + TypeScript
- Apache Spark / PySpark
- Model Context Protocol Java SDK
- Gemini API through OpenAI-compatible endpoint

## Project Structure

```text
.
|-- src/main/java/com/datawarehouse/datawarehouse
|   |-- config
|   |-- dal
|   |-- domain
|   |-- ingestion
|   |-- service
|   `-- web
|-- frontend
|   `-- React source code
|-- spark_analysis_ml
|   |-- compute_yearly_summaries.py
|   |-- train_price_regression.py
|   |-- requirements.txt
|   `-- run_spark.ps1
|-- mcp_server_java
|   `-- Java MCP server
|-- docker-compose.yml
|-- build.gradle
`-- README.md
```

## Requirements Coverage

| Lab / Requirement Area | Implementation |
| --- | --- |
| Data ingestion | Nasdaq Data Link, Alpha Vantage, Binance streaming ingestion |
| Database storage | MongoDB collections for assets, data sources, time-series data, ingestion jobs, analytics outputs |
| Consumption API | `/api/v1/assets`, `/api/v1/data-sources`, `/api/v1/data`, `/api/v1/data/stream` |
| Bitemporal data handling | `businessDate`, `systemDate`, latest-visible query logic, deletion markers |
| Batch writes | Ingestion services persist transformed records in batches |
| Asynchronous processing | Kafka producer/consumer flow for ingestion jobs |
| Streaming | Binance WebSocket client publishes raw market events to Kafka |
| Analytics and data mining | PySpark yearly summaries and price regression jobs |
| LLM / Agentic AI consumer | MCP server plus `/api/v1/assistant/chat` and React LLM Assistant UI |
| MCP tool layer | `list_assets`, `get_asset_details`, `list_data_sources`, `get_data_source_details`, `get_time_series_data` |
| Pagination and bounds | Offset/limit pagination, bounded time-series ranges, validation of limits and dates |
| Provenance and temporal semantics | MCP and assistant responses include provenance and temporal semantics |

## Running Infrastructure

Start MongoDB and Kafka:

```powershell
cd C:\Users\adelg\Downloads\datawarehouse\datawarehouse
docker compose up -d
```

MongoDB runs on:

```text
localhost:27017
```

Kafka runs on:

```text
localhost:9092
```

## Environment Variables

Set only the keys you need for the flows you want to run:

```powershell
$env:NASDAQ_DATA_LINK_API_KEY="your_nasdaq_data_link_key"
$env:ALPHAVANTAGE_API_KEY="your_alpha_vantage_key"
$env:GEMINI_API_KEY="your_gemini_key"
```

Optional LLM overrides:

```powershell
$env:LLM_API_BASE="https://generativelanguage.googleapis.com/v1beta/openai"
$env:LLM_MODEL="gemini-2.5-flash"
```

No API keys are committed to the repository. Local secret files such as `application-local.properties` are ignored.

## Running the Application

Build the React UI once before starting Spring Boot. The generated files are written to
`src/main/resources/static/` and are intentionally ignored by Git to keep repository
evaluation compact.

```powershell
cd C:\Users\adelg\Downloads\datawarehouse\datawarehouse\frontend
cmd /c npm run build
```

```powershell
cd C:\Users\adelg\Downloads\datawarehouse\datawarehouse
.\gradlew.bat bootRun --no-daemon
```

Open:

```text
http://127.0.0.1:8081
```

Main UI routes:

- `/` dashboard
- `/instruments`
- `/timeseries`
- `/ingestion`
- `/analytics`
- `/assistant`

## REST API Summary

Formal OpenAPI documentation is available in the repository at
`src/main/resources/api/openapi.yaml` and is exposed by the running application:

```text
GET /api/v1/openapi.yaml
```

### Warehouse Read API

```text
GET /api/v1/assets?offset=0&limit=20
GET /api/v1/assets/{assetId}
GET /api/v1/data-sources?offset=0&limit=20
GET /api/v1/data-sources/{dataSourceId}
GET /api/v1/data?assetId=...&dataSourceId=...&startBusinessDate=YYYY-MM-DD&endBusinessDate=YYYY-MM-DD
GET /api/v1/data/stream?assetId=...&dataSourceId=...&startBusinessDate=YYYY-MM-DD&endBusinessDate=YYYY-MM-DD
```

### Ingestion API

```text
POST /api/v1/ingestions/{provider}/{assetId}
POST /api/v1/ingestions/jobs/{provider}/{assetId}
GET  /api/v1/ingestions/jobs/{jobId}
GET  /api/v1/ingestions/jobs
```

Examples:

```powershell
Invoke-RestMethod -Method Post "http://127.0.0.1:8081/api/v1/ingestions/nasdaq/ZRXUSD"
Invoke-RestMethod -Method Post "http://127.0.0.1:8081/api/v1/ingestions/alphavantage/IBM"
```

### Binance Streaming API

```text
POST /api/v1/streaming/binance/start
POST /api/v1/streaming/binance/stop
GET  /api/v1/streaming/binance/status
```

### Analytics API

```text
GET  /api/v1/analytics/jobs
GET  /api/v1/analytics/yearly-summaries
GET  /api/v1/analytics/predictions
POST /api/v1/analytics/run/yearly-summaries
POST /api/v1/analytics/run/price-regression
POST /api/v1/analytics/run/all
```

### LLM Assistant API

```text
POST /api/v1/assistant/chat
```

Example body:

```json
{
  "message": "Analyze the selected asset trend using warehouse records.",
  "assetId": "BINANCE/SPOT/BTCUSDT",
  "dataSourceId": "BINANCE/SPOT",
  "startBusinessDate": "2026-05-01",
  "endBusinessDate": "2026-05-31",
  "includeAttributes": true
}
```

## Spark Analytics

Create and activate the Spark Python environment:

```powershell
cd C:\Users\adelg\Downloads\datawarehouse\datawarehouse\spark_analysis_ml
py -3.11 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
```

Run yearly aggregation:

```powershell
.\run_spark.ps1 .\compute_yearly_summaries.py
```

Run ML regression:

```powershell
.\run_spark.ps1 .\train_price_regression.py
```

Spark outputs are written back to MongoDB:

- `analytics_yearly_summaries`
- `analytics_price_predictions`

## MCP Server

The Java MCP server is read-only and is built on top of the existing Spring Boot consumption API. It does not read MongoDB directly.

Build:

```powershell
cd C:\Users\adelg\Downloads\datawarehouse\datawarehouse
.\gradlew.bat -p mcp_server_java clean build
.\gradlew.bat -p mcp_server_java installDist
```

MCP Inspector configuration:

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

MCP tools:

- `list_assets`
- `get_asset_details`
- `list_data_sources`
- `get_data_source_details`
- `get_time_series_data`

The server also exposes a prompt and a resource describing the multi-step agentic workflow.

## LLM Assistant

The React LLM Assistant UI is available at:

```text
http://127.0.0.1:8081/assistant
```

The UI sends prompts to:

```text
POST /api/v1/assistant/chat
```

The backend performs an agentic warehouse workflow:

1. Discover assets.
2. Discover data sources.
3. Resolve selected asset and source.
4. Read asset metadata.
5. Read data-source metadata.
6. Read bounded time-series records.
7. Build a structured context.
8. Ask Gemini for a concise summary if `GEMINI_API_KEY` is configured.
9. Fall back to a deterministic local summary if no LLM key is configured.

The assistant is read-only and does not provide investment advice.

## Build Verification

Build the Spring Boot application:

```powershell
cd C:\Users\adelg\Downloads\datawarehouse\datawarehouse
.\gradlew.bat build
```

Run the Java unit tests only:

```powershell
.\gradlew.bat test
```

Build the frontend production bundle:

```powershell
cd C:\Users\adelg\Downloads\datawarehouse\datawarehouse\frontend
cmd /c npm install
cmd /c npm run build
```

Build the MCP server:

```powershell
cd C:\Users\adelg\Downloads\datawarehouse\datawarehouse
.\gradlew.bat -p mcp_server_java build
```

## Demo Video

The required end-to-end demo video is not generated by the codebase. Use
`docs/demo-video-script.md` as the recording checklist. The video should show:

- ingestion from at least one provider
- MongoDB-backed REST consumption
- Spark yearly summaries and price regression
- MCP / LLM assistant grounded answers

## Submission Notes

Generated and local runtime files are ignored:

- `.gradle/`
- `build/`
- `frontend/node_modules/`
- `frontend/dist/`
- `frontend/package-lock.json`
- `src/main/resources/static/`
- `spark_analysis_ml/.venv/`
- `spark_analysis_ml/spark-warehouse/`
- `mcp_server_java/build/`
- `mcp_server_java/.gradle/`
- local secret files

The repository contains source code, configuration, Spark jobs, and MCP server code. The production React bundle is generated locally with `cmd /c npm run build`.
