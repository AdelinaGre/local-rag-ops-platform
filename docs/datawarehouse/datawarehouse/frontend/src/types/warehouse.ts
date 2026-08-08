export interface FinancialInstrument {
  id: string;
  symbol: string;
  name: string;
  class: string;
  region?: string;
  dataSource?: string;
  description?: string;
  systemDate?: string;
}

export interface TimeSeriesPoint {
  date: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

export interface DataSource {
  id: string;
  name: string;
  type: string;
  provider?: string;
  dataset?: string;
  lastSync?: string;
  attributes: string[];
}

export interface IngestionResult {
  fetchedRecords?: number;
  transformedRecords?: number;
  storedRecords?: number;
  skippedRecords?: number;
  failedRecords?: number;
  message?: string;
}

export type MarketDataProviderId = "nasdaq" | "alphavantage";

export interface StreamingStatus {
  provider: string;
  status: "RUNNING" | "STOPPED" | string;
  running?: boolean;
  message?: string;
  checkedAt?: string;
  startedAt?: string | null;
  stoppedAt?: string | null;
  lastMessageAt?: string | null;
  lastCloseStatus?: string | null;
  lastError?: string | null;
}

export interface YearlySummary {
  assetId: string;
  assetSymbol?: string;
  dataSourceId: string;
  businessYear: number;
  count: number;
  minClose?: number | null;
  maxClose?: number | null;
  avgClose?: number | null;
  avgVolume?: number | null;
  computedAt?: string;
}

export interface PricePrediction {
  assetId: string;
  assetSymbol?: string;
  dataSourceId?: string;
  date: string;
  actualClose?: number | null;
  predictedClose?: number | null;
  residual?: number | null;
  modelR2?: number | null;
  modelRMSE?: number | null;
  computedAt?: string;
}

export interface SparkJobStatus {
  jobId: string;
  name: string;
  type: "aggregation" | "ml_regression" | string;
  status: "completed" | "running" | "failed" | "pending" | string;
  startedAt?: string | null;
  completedAt?: string | null;
  recordsProcessed?: number | null;
  outputCollection?: string | null;
  error?: string | null;
}

export interface AssistantChatRequest {
  message: string;
  assetId?: string;
  dataSourceId?: string;
  startBusinessDate?: string;
  endBusinessDate?: string;
  includeAttributes?: boolean;
}

export interface AssistantToolCall {
  name: string;
  arguments: Record<string, unknown>;
  status: "OK" | "EMPTY" | string;
  summary: string;
}

export interface AssistantChatResponse {
  answer: string;
  llmUsed: boolean;
  model: string;
  toolCalls: AssistantToolCall[];
  context: Record<string, unknown>;
}
