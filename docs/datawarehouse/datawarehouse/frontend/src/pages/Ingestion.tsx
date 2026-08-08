import { useEffect, useState } from "react";
import { Play, Radio, RefreshCw, Square } from "lucide-react";
import EmptyState from "@/components/EmptyState";
import type {
  DataSource,
  FinancialInstrument,
  IngestionResult,
  MarketDataProviderId,
  StreamingStatus,
} from "@/types/warehouse";
import {
  ingestAsset,
  loadBinanceStreamingStatus,
  loadDataSources,
  loadInstruments,
  startBinanceStreaming,
  stopBinanceStreaming,
} from "@/lib/warehouseApi";

type Job = {
  provider: MarketDataProviderId;
  assetId: string;
  status: "completed" | "running" | "error";
  fetched: number;
  stored: number;
  skipped: number;
  failed: number;
  time: string;
  message?: string;
};

const JOBS_STORAGE_KEY = "market-dwh-ingestion-runs";

const PROVIDERS: Array<{
  id: MarketDataProviderId;
  label: string;
  placeholder: string;
}> = [
  {
    id: "nasdaq",
    label: "Nasdaq Data Link",
    placeholder: "Asset code, e.g. ZRXUSD",
  },
  {
    id: "alphavantage",
    label: "Alpha Vantage",
    placeholder: "Stock symbol, e.g. IBM",
  },
];

export default function Ingestion() {
  const [dataSources, setDataSources] = useState<DataSource[]>([]);
  const [instruments, setInstruments] = useState<FinancialInstrument[]>([]);
  const [provider, setProvider] = useState<MarketDataProviderId>("nasdaq");
  const [assetCode, setAssetCode] = useState("");
  const [isSyncing, setIsSyncing] = useState(false);
  const [isStreamingAction, setIsStreamingAction] = useState(false);
  const [error, setError] = useState("");
  const [streamingError, setStreamingError] = useState("");
  const [streamingStatus, setStreamingStatus] = useState<StreamingStatus | null>(null);
  const [jobs, setJobs] = useState<Job[]>(() => loadStoredJobs());
  const selectedProvider = PROVIDERS.find((item) => item.id === provider) ?? PROVIDERS[0];
  const streamIsRunning = Boolean(streamingStatus?.running || streamingStatus?.status === "RUNNING");

  async function refreshWarehouseState() {
    const [nextSources, nextInstruments] = await Promise.all([
      loadDataSources(),
      loadInstruments(),
    ]);

    setDataSources(nextSources);
    setInstruments(nextInstruments);
  }

  useEffect(() => {
    let cancelled = false;

    async function load() {
      const [nextSources, nextInstruments, nextStreamingStatus] = await Promise.all([
        loadDataSources(),
        loadInstruments(),
        loadBinanceStreamingStatus(),
      ]);

      if (!cancelled) {
        setDataSources(nextSources);
        setInstruments(nextInstruments);
        setStreamingStatus(nextStreamingStatus);
      }
    }

    load();

    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!streamIsRunning) {
      return;
    }

    const interval = window.setInterval(() => {
      refreshStreamingStatus();
    }, 5000);

    return () => window.clearInterval(interval);
  }, [streamIsRunning]);

  useEffect(() => {
    storeJobs(jobs);
  }, [jobs]);

  async function handleRunIngest() {
    const normalizedAsset = assetCode.trim();
    if (!normalizedAsset) {
      setError("Enter an asset code before running ingestion.");
      return;
    }

    setError("");
    setIsSyncing(true);
    const started = performance.now();

    try {
      const result = await ingestAsset(provider, normalizedAsset);
      setJobs((current) => [toJob(provider, normalizedAsset, result, started), ...current.slice(0, 5)]);
      await refreshWarehouseState();
    } catch (err) {
      setJobs((current) => [
        {
          provider,
          assetId: normalizedAsset,
          status: "error",
          fetched: 0,
          stored: 0,
          skipped: 0,
          failed: 1,
          time: elapsed(started),
          message: err instanceof Error ? err.message : "Ingestion failed",
        },
        ...current.slice(0, 5),
      ]);
      setError(err instanceof Error ? err.message : "Ingestion failed.");
    } finally {
      setIsSyncing(false);
    }
  }

  async function refreshStreamingStatus() {
    const nextStatus = await loadBinanceStreamingStatus();
    setStreamingStatus(nextStatus);
  }

  async function handleStartStreaming() {
    setStreamingError("");
    setIsStreamingAction(true);

    try {
      const result = await startBinanceStreaming();
      setStreamingStatus(result);
      window.setTimeout(() => {
        refreshStreamingStatus();
        refreshWarehouseState();
      }, 1500);
    } catch (err) {
      setStreamingError(err instanceof Error ? err.message : "Could not start Binance streaming.");
    } finally {
      setIsStreamingAction(false);
    }
  }

  async function handleStopStreaming() {
    setStreamingError("");
    setIsStreamingAction(true);

    try {
      const result = await stopBinanceStreaming();
      setStreamingStatus(result);
      await refreshWarehouseState();
    } catch (err) {
      setStreamingError(err instanceof Error ? err.message : "Could not stop Binance streaming.");
    } finally {
      setIsStreamingAction(false);
    }
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-foreground">Data Ingestion</h1>
        <p className="text-sm text-muted-foreground mt-1">
          Provider data loaded through the backend ingestion API
        </p>
      </div>

      <div className="rounded-xl border border-border bg-card p-5">
        <div className="flex flex-col gap-3 sm:flex-row">
          <select
            value={provider}
            onChange={(event) => setProvider(event.target.value as MarketDataProviderId)}
            className="rounded-lg border border-border bg-background px-4 py-2.5 text-sm font-medium text-card-foreground focus:outline-none focus:ring-2 focus:ring-ring sm:w-56"
            aria-label="Market data provider"
          >
            {PROVIDERS.map((item) => (
              <option key={item.id} value={item.id}>
                {item.label}
              </option>
            ))}
          </select>
          <input
            type="text"
            value={assetCode}
            onChange={(event) => setAssetCode(event.target.value)}
            placeholder={selectedProvider.placeholder}
            className="flex-1 rounded-lg border border-border bg-background px-4 py-2.5 text-sm text-card-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          />
          <button
            onClick={handleRunIngest}
            disabled={isSyncing}
            className="inline-flex items-center justify-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground transition-opacity hover:opacity-90 disabled:opacity-60"
          >
            <RefreshCw className={`h-4 w-4 ${isSyncing ? "animate-spin" : ""}`} />
            Run Ingest
          </button>
        </div>
        {error && <p className="mt-3 text-xs text-chart-down">{error}</p>}
      </div>

      <div className="rounded-xl border border-border bg-card p-5">
        <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
          <div className="flex items-start gap-3">
            <div className="mt-0.5 rounded-lg border border-border bg-background p-2 text-primary">
              <Radio className="h-4 w-4" />
            </div>
            <div>
              <div className="flex flex-wrap items-center gap-2">
                <h2 className="text-sm font-semibold text-card-foreground">Binance Streaming</h2>
                <span
                  className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${
                    streamIsRunning
                      ? "bg-chart-up/15 text-chart-up"
                      : "bg-muted text-muted-foreground"
                  }`}
                >
                  {streamIsRunning ? "RUNNING" : "STOPPED"}
                </span>
              </div>
              <p className="mt-1 text-xs text-muted-foreground">
                BTCUSDT and ETHUSDT kline events are streamed through Kafka into MongoDB.
              </p>
              <div className="mt-3 grid gap-2 text-xs text-muted-foreground sm:grid-cols-3">
                <span>
                  Last message:{" "}
                  <strong className="font-mono text-card-foreground">
                    {formatDateTime(streamingStatus?.lastMessageAt)}
                  </strong>
                </span>
                <span>
                  Started:{" "}
                  <strong className="font-mono text-card-foreground">
                    {formatDateTime(streamingStatus?.startedAt)}
                  </strong>
                </span>
                <span>
                  Checked:{" "}
                  <strong className="font-mono text-card-foreground">
                    {formatDateTime(streamingStatus?.checkedAt)}
                  </strong>
                </span>
              </div>
              {streamingStatus?.lastError && (
                <p className="mt-2 text-xs text-chart-down">{streamingStatus.lastError}</p>
              )}
              {streamingError && <p className="mt-2 text-xs text-chart-down">{streamingError}</p>}
            </div>
          </div>

          <div className="flex flex-wrap gap-2">
            <button
              onClick={handleStartStreaming}
              disabled={isStreamingAction || streamIsRunning}
              className="inline-flex items-center justify-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground transition-opacity hover:opacity-90 disabled:opacity-60"
            >
              <Play className="h-4 w-4" />
              Start Stream
            </button>
            <button
              onClick={handleStopStreaming}
              disabled={isStreamingAction || !streamIsRunning}
              className="inline-flex items-center justify-center gap-2 rounded-lg border border-border bg-background px-4 py-2.5 text-sm font-medium text-card-foreground transition-colors hover:bg-muted disabled:opacity-60"
            >
              <Square className="h-4 w-4" />
              Stop
            </button>
            <button
              onClick={() => {
                refreshStreamingStatus();
                refreshWarehouseState();
              }}
              disabled={isStreamingAction}
              className="inline-flex items-center justify-center gap-2 rounded-lg border border-border bg-background px-4 py-2.5 text-sm font-medium text-card-foreground transition-colors hover:bg-muted disabled:opacity-60"
            >
              <RefreshCw className={`h-4 w-4 ${isStreamingAction ? "animate-spin" : ""}`} />
              Refresh
            </button>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {dataSources.length ? (
          dataSources.map((source) => {
            return (
              <div
                key={source.id}
                className="rounded-xl border border-border bg-card p-5 space-y-4"
              >
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <h3 className="truncate text-sm font-semibold text-card-foreground">{source.name}</h3>
                    <p className="mt-0.5 truncate text-xs text-muted-foreground">{source.id}</p>
                  </div>
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <span className="text-xs text-muted-foreground">Provider</span>
                    <p className="mt-1 truncate text-sm font-medium text-card-foreground">
                      {source.provider || "-"}
                    </p>
                  </div>
                  <div>
                    <span className="text-xs text-muted-foreground">Dataset</span>
                    <p className="mt-1 truncate text-sm font-medium text-card-foreground">
                      {source.dataset || "-"}
                    </p>
                  </div>
                  <div>
                    <span className="text-xs text-muted-foreground">Attributes</span>
                    <p className="mt-1 font-mono text-sm font-medium text-card-foreground">
                      {source.attributes.length}
                    </p>
                  </div>
                  <div>
                    <span className="text-xs text-muted-foreground">Last Sync</span>
                    <p className="mt-1 text-xs font-mono text-card-foreground">
                      {source.lastSync ? new Date(source.lastSync).toLocaleString() : "-"}
                    </p>
                  </div>
                </div>
              </div>
            );
          })
        ) : (
          <div className="md:col-span-2">
            <EmptyState
              title="No data sources in MongoDB"
              description="Run ingestion after configuring a real provider in the backend."
            />
          </div>
        )}
      </div>

      <div className="rounded-xl border border-border bg-card p-5">
        <h2 className="text-sm font-semibold text-card-foreground mb-4">
          Ingestion Runs
        </h2>
        {jobs.length ? (
          <div className="space-y-2">
            {jobs.map((job, index) => (
              <div
                key={`${job.provider}-${job.assetId}-${index}`}
                className="grid gap-3 rounded-lg px-3 py-2.5 transition-colors hover:bg-muted/50 sm:grid-cols-[1fr_auto]"
              >
                <div className="flex items-center gap-3">
                  <div
                    className={`h-2 w-2 rounded-full ${
                      job.status === "completed"
                        ? "bg-chart-up"
                        : job.status === "error"
                          ? "bg-chart-down"
                          : "bg-primary animate-pulse"
                    }`}
                  />
                  <div>
                    <span className="text-sm text-card-foreground">{job.assetId}</span>
                    <p className="text-xs text-muted-foreground">{formatProvider(job.provider)}</p>
                    {job.message && <p className="text-xs text-muted-foreground">{job.message}</p>}
                  </div>
                </div>
                <div className="flex flex-wrap items-center gap-4 text-xs text-muted-foreground">
                  <span className="font-mono">fetched {job.fetched.toLocaleString()}</span>
                  <span className="font-mono">stored {job.stored.toLocaleString()}</span>
                  <span className="font-mono">skipped {job.skipped.toLocaleString()}</span>
                  <span className="font-mono">failed {job.failed.toLocaleString()}</span>
                  <span className="font-mono">{job.time}</span>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <EmptyState title="No ingestion runs in this browser session" />
        )}
      </div>
    </div>
  );
}

function loadStoredJobs(): Job[] {
  try {
    const raw = window.localStorage.getItem(JOBS_STORAGE_KEY);
    if (!raw) {
      return [];
    }

    const parsed = JSON.parse(raw);
    return Array.isArray(parsed)
      ? parsed
          .filter((job) => job && typeof job.assetId === "string")
          .map((job) => ({
            ...job,
            provider: isProvider(job.provider) ? job.provider : "nasdaq",
          }))
          .slice(0, 6)
      : [];
  } catch {
    return [];
  }
}

function storeJobs(jobs: Job[]) {
  try {
    window.localStorage.setItem(JOBS_STORAGE_KEY, JSON.stringify(jobs.slice(0, 6)));
  } catch {
    // Browser storage can be unavailable; ingestion still works without local history.
  }
}

function toJob(
  provider: MarketDataProviderId,
  assetId: string,
  result: IngestionResult,
  started: number,
): Job {
  const failed = Number(result.failedRecords ?? 0);

  return {
    provider,
    assetId,
    status: failed > 0 ? "error" : "completed",
    fetched: Number(result.fetchedRecords ?? 0),
    stored: Number(result.storedRecords ?? 0),
    skipped: Number(result.skippedRecords ?? 0),
    failed,
    time: elapsed(started),
    message: result.message,
  };
}

function elapsed(started: number) {
  return `${Math.max(1, Math.round((performance.now() - started) / 1000))}s`;
}

function isProvider(value: unknown): value is MarketDataProviderId {
  return PROVIDERS.some((item) => item.id === value);
}

function formatProvider(value: MarketDataProviderId) {
  return PROVIDERS.find((item) => item.id === value)?.label ?? value;
}

function formatDateTime(value?: string | null) {
  if (!value) {
    return "-";
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "-";
  }

  return date.toLocaleString();
}
