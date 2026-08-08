import type {
  DataSource,
  FinancialInstrument,
  AssistantChatRequest,
  AssistantChatResponse,
  IngestionResult,
  MarketDataProviderId,
  PricePrediction,
  SparkJobStatus,
  StreamingStatus,
  TimeSeriesPoint,
  YearlySummary,
} from "@/types/warehouse";

type PagedIdsResponse = {
  items?: string[];
  hasNext?: boolean;
  offset?: number;
  limit?: number;
};

type AssetResponse = {
  id?: string;
  name?: string;
  description?: string;
  symbol?: string;
  assetType?: string;
  systemDate?: string;
  attributes?: Record<string, string>;
};

type DataSourceResponse = {
  id?: string;
  name?: string;
  description?: string;
  systemDate?: string;
  provider?: string;
  dataset?: string;
  requestContext?: Record<string, string>;
  attributes?: string[];
};

type TimeSeriesResponse = {
  data?: Array<{
    businessDate?: string;
    systemDate?: string;
    payload?: Record<string, unknown>;
  }>;
  attributes?: string[];
  hasNext?: boolean;
};

type ListResponse<T> = T[] | {
  items?: T[];
  data?: T[];
};

const API_BASE = "/api/v1";

export async function loadInstruments(): Promise<FinancialInstrument[]> {
  try {
    const ids = await loadAllIds(`${API_BASE}/assets`);
    if (!ids.length) {
      return [];
    }

    const details = await Promise.all(
      ids.map((id) => fetchJson<AssetResponse>(`${API_BASE}/assets/${pathId(id)}`).catch(() => ({ id }))),
    );

    return details.map((asset, index) => toInstrument(asset, ids[index], index));
  } catch (error) {
    console.error("Could not load instruments from warehouse API", error);
    return [];
  }
}

export async function loadDataSources(): Promise<DataSource[]> {
  try {
    const ids = await loadAllIds(`${API_BASE}/data-sources`);
    if (!ids.length) {
      return [];
    }

    const details = await Promise.all(
      ids.map((id) =>
        fetchJson<DataSourceResponse>(`${API_BASE}/data-sources/${pathId(id)}`).catch(() => ({ id })),
      ),
    );

    return details.map((source, index) => toDataSource(source, ids[index], index));
  } catch (error) {
    console.error("Could not load data sources from warehouse API", error);
    return [];
  }
}

export async function ingestAsset(
  providerId: MarketDataProviderId | string,
  assetId: string,
): Promise<IngestionResult> {
  const provider = String(providerId ?? "").trim();
  const normalized = normalizeIngestionAssetId(assetId);
  if (!provider) {
    throw new Error("Market data provider is required.");
  }

  if (!normalized) {
    throw new Error("Asset code is required.");
  }

  return fetchJson<IngestionResult>(
    `${API_BASE}/ingestions/${encodeURIComponent(provider)}/${encodeURIComponent(normalized)}`,
    {
      method: "POST",
    },
  );
}

export async function loadBinanceStreamingStatus(): Promise<StreamingStatus | null> {
  try {
    return fetchJson<StreamingStatus>(`${API_BASE}/streaming/binance/status`);
  } catch (error) {
    console.error("Could not load Binance streaming status", error);
    return null;
  }
}

export async function startBinanceStreaming(): Promise<StreamingStatus> {
  return fetchJson<StreamingStatus>(`${API_BASE}/streaming/binance/start`, {
    method: "POST",
  });
}

export async function stopBinanceStreaming(): Promise<StreamingStatus> {
  return fetchJson<StreamingStatus>(`${API_BASE}/streaming/binance/stop`, {
    method: "POST",
  });
}

export async function loadTimeSeries(
  assetId: string,
  dataSourceId: string,
  days = 30,
): Promise<TimeSeriesPoint[]> {
  if (!assetId || !dataSourceId) {
    return [];
  }

  const end = new Date();
  end.setDate(end.getDate() + 1);

  try {
    const params = new URLSearchParams({
      assetId,
      dataSourceId,
      startBusinessDate: "1900-01-01",
      endBusinessDate: toDateInput(end),
      includeAttributes: "true",
      offset: "0",
      limit: String(days),
    });

    const response = await fetchJson<TimeSeriesResponse>(`${API_BASE}/data?${params.toString()}`);
    const rows = response.data ?? [];

    return rows
      .map((row) => toTimeSeriesPoint(row.businessDate, row.payload))
      .filter(Boolean)
      .reverse()
      .slice(-days) as TimeSeriesPoint[];
  } catch (error) {
    console.error("Could not load time series from warehouse API", error);
    return [];
  }
}

export async function loadSparkJobs(): Promise<SparkJobStatus[]> {
  try {
    const response = await fetchJson<ListResponse<SparkJobStatus>>(`${API_BASE}/analytics/jobs`);
    return unwrapList(response);
  } catch {
    return [];
  }
}

export async function runYearlySummaries(assetId?: string): Promise<SparkJobStatus> {
  return fetchJson<SparkJobStatus>(`${API_BASE}/analytics/run/yearly-summaries${assetQuery(assetId)}`, {
    method: "POST",
  });
}

export async function runPriceRegression(assetId?: string): Promise<SparkJobStatus> {
  return fetchJson<SparkJobStatus>(`${API_BASE}/analytics/run/price-regression${assetQuery(assetId)}`, {
    method: "POST",
  });
}

export async function runAllAnalytics(assetId?: string): Promise<SparkJobStatus[]> {
  const response = await fetchJson<ListResponse<SparkJobStatus>>(`${API_BASE}/analytics/run/all${assetQuery(assetId)}`, {
    method: "POST",
  });
  return unwrapList(response);
}

export async function loadYearlySummaries(): Promise<YearlySummary[]> {
  try {
    const response = await fetchJson<ListResponse<YearlySummary>>(`${API_BASE}/analytics/yearly-summaries`);
    return unwrapList(response).map(normalizeYearlySummary).filter(Boolean) as YearlySummary[];
  } catch {
    return [];
  }
}

export async function loadPricePredictions(assetId?: string): Promise<PricePrediction[]> {
  const params = new URLSearchParams();
  if (assetId) {
    params.set("assetId", assetId);
  }

  try {
    const suffix = params.size ? `?${params.toString()}` : "";
    const response = await fetchJson<ListResponse<PricePrediction>>(`${API_BASE}/analytics/predictions${suffix}`);
    return unwrapList(response).map(normalizePrediction).filter(Boolean) as PricePrediction[];
  } catch {
    return [];
  }
}

export async function chatWithAssistant(request: AssistantChatRequest): Promise<AssistantChatResponse> {
  return fetchJson<AssistantChatResponse>(`${API_BASE}/assistant/chat`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(request),
  });
}

async function loadAllIds(endpoint: string) {
  const ids: string[] = [];
  let offset = 0;
  const limit = 100;

  while (true) {
    const page = await fetchJson<PagedIdsResponse>(`${endpoint}?offset=${offset}&limit=${limit}`);
    ids.push(...(page.items ?? []));
    if (!page.hasNext) {
      break;
    }
    offset += limit;
  }

  return ids;
}

function assetQuery(assetId?: string) {
  if (!assetId) {
    return "";
  }
  const params = new URLSearchParams({ assetId });
  return `?${params.toString()}`;
}

async function fetchJson<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, init);
  const contentType = response.headers.get("content-type") ?? "";
  const text = await response.text();

  if (!response.ok) {
    throw new Error(readErrorMessage(text) || `${response.status} ${response.statusText}`);
  }

  if (!contentType.includes("application/json")) {
    throw new Error("Expected JSON response.");
  }

  return JSON.parse(text) as T;
}

function readErrorMessage(text: string) {
  if (!text) {
    return "";
  }

  try {
    const parsed = JSON.parse(text) as { message?: string; error?: string };
    return parsed.message || parsed.error || text;
  } catch {
    return text;
  }
}

function unwrapList<T>(response: ListResponse<T>): T[] {
  if (Array.isArray(response)) {
    return response;
  }
  return response.items ?? response.data ?? [];
}

function normalizeYearlySummary(summary: YearlySummary) {
  if (!summary || !summary.assetId || !summary.dataSourceId || !summary.businessYear) {
    return null;
  }

  return {
    ...summary,
    assetSymbol: summary.assetSymbol || lastSegment(summary.assetId),
    count: Number(summary.count ?? 0),
    minClose: nullableNumber(summary.minClose),
    maxClose: nullableNumber(summary.maxClose),
    avgClose: nullableNumber(summary.avgClose),
    avgVolume: nullableNumber(summary.avgVolume),
  };
}

function normalizePrediction(prediction: PricePrediction) {
  if (!prediction || !prediction.assetId || !prediction.date) {
    return null;
  }

  return {
    ...prediction,
    assetSymbol: prediction.assetSymbol || lastSegment(prediction.assetId),
    actualClose: nullableNumber(prediction.actualClose),
    predictedClose: nullableNumber(prediction.predictedClose),
    residual: nullableNumber(prediction.residual),
    modelR2: nullableNumber(prediction.modelR2),
    modelRMSE: nullableNumber(prediction.modelRMSE),
  };
}

function nullableNumber(value: unknown) {
  if (value === null || value === undefined || value === "") {
    return null;
  }
  const numberValue = Number(value);
  return Number.isFinite(numberValue) ? numberValue : null;
}

function toInstrument(asset: AssetResponse, fallbackId: string, index: number): FinancialInstrument {
  const id = asset.id || fallbackId;
  const symbol = asset.symbol || lastSegment(id) || `ASSET-${index + 1}`;
  const assetClass = asset.assetType || asset.attributes?.class || asset.attributes?.type || "Market";

  return {
    id,
    symbol,
    name: asset.name || asset.description || symbol,
    class: normalizeAssetClass(assetClass),
    region: asset.attributes?.region || asset.attributes?.country,
    dataSource: asset.attributes?.dataSourceId || asset.attributes?.provider,
    description: asset.description,
    systemDate: asset.systemDate,
  };
}

function toDataSource(source: DataSourceResponse, fallbackId: string, index: number): DataSource {
  const id = source.id || fallbackId;
  const provider = source.provider || source.name || lastSegment(id) || `Source ${index + 1}`;
  const dataset = source.dataset;
  const name = source.name || [provider, dataset].filter(Boolean).join(" / ") || id;

  return {
    id,
    name,
    type: source.description || source.requestContext?.sourceType || "REST API",
    provider,
    dataset,
    lastSync: source.systemDate,
    attributes: source.attributes ?? [],
  };
}

function toTimeSeriesPoint(date: string | undefined, payload: Record<string, unknown> | undefined) {
  if (!date || !payload) {
    return null;
  }

  const close = readNumber(payload, ["close", "last", "mid", "price", "Close", "Last"]);
  const open = readNumber(payload, ["open", "first", "Open"]) || close;
  const high = readNumber(payload, ["high", "High"]) || Math.max(open, close);
  const low = readNumber(payload, ["low", "Low"]) || Math.min(open, close);
  const volume = readNumber(payload, ["volume", "Volume", "vol"]) || 0;

  return {
    date: date.slice(0, 10),
    open,
    high,
    low,
    close,
    volume,
  };
}

function readNumber(payload: Record<string, unknown>, keys: string[]) {
  for (const key of keys) {
    const value = Number(payload[key]);
    if (Number.isFinite(value)) {
      return value;
    }
  }
  return 0;
}

function pathId(value: string) {
  return String(value)
    .split("/")
    .filter(Boolean)
    .map(encodeURIComponent)
    .join("/");
}

function lastSegment(value: string) {
  return String(value).split("/").filter(Boolean).pop() ?? value;
}

function normalizeAssetClass(value: string) {
  const normalized = String(value || "").toLowerCase();
  if (normalized.includes("crypto")) return "Crypto";
  if (normalized.includes("stock") || normalized.includes("equity")) return "Stock";
  if (normalized.includes("commodity")) return "Commodity";
  if (normalized.includes("forex") || normalized.includes("fx")) return "Forex";
  if (normalized.includes("bond")) return "Bond";
  return value || "Market";
}

function normalizeIngestionAssetId(value: string) {
  const cleaned = String(value ?? "").trim();
  if (!cleaned.includes("/")) {
    return cleaned;
  }

  return cleaned.split("/").filter(Boolean).pop() ?? cleaned;
}

function toDateInput(date: Date) {
  return date.toISOString().slice(0, 10);
}
