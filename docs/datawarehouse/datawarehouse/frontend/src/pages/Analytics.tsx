import { useEffect, useMemo, useState } from "react";
import { Play, RefreshCw } from "lucide-react";
import {
  Bar,
  BarChart,
  ComposedChart,
  Legend,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import EmptyState from "@/components/EmptyState";
import type {
  DataSource,
  FinancialInstrument,
  PricePrediction,
  SparkJobStatus,
  YearlySummary,
} from "@/types/warehouse";
import {
  loadDataSources,
  loadInstruments,
  loadPricePredictions,
  loadSparkJobs,
  loadYearlySummaries,
  runAllAnalytics,
  runPriceRegression,
  runYearlySummaries,
} from "@/lib/warehouseApi";

const TOOLTIP_STYLE = {
  backgroundColor: "hsl(0, 0%, 6%)",
  border: "1px solid hsl(0, 0%, 14%)",
  borderRadius: "8px",
  fontSize: 12,
  color: "hsl(0, 0%, 95%)",
};

const AXIS_TICK = { fontSize: 10, fill: "hsl(0, 0%, 45%)" };

function StatusBadge({ status }: { status: SparkJobStatus["status"] }) {
  const normalized = String(status || "pending").toLowerCase();
  const map: Record<string, { bg: string; text: string; label: string }> = {
    completed: { bg: "bg-emerald-500/15", text: "text-emerald-400", label: "Completed" },
    running: { bg: "bg-primary/15", text: "text-primary", label: "Running" },
    failed: { bg: "bg-destructive/15", text: "text-destructive", label: "Failed" },
    pending: { bg: "bg-muted", text: "text-muted-foreground", label: "Pending" },
  };
  const style = map[normalized] ?? map.pending;

  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium ${style.bg} ${style.text}`}>
      {normalized === "running" && <span className="h-1.5 w-1.5 rounded-full bg-current animate-pulse" />}
      {normalized === "completed" && <span className="h-1.5 w-1.5 rounded-full bg-current" />}
      {style.label}
    </span>
  );
}

export default function Analytics() {
  const [jobs, setJobs] = useState<SparkJobStatus[]>([]);
  const [summaries, setSummaries] = useState<YearlySummary[]>([]);
  const [predictions, setPredictions] = useState<PricePrediction[]>([]);
  const [instruments, setInstruments] = useState<FinancialInstrument[]>([]);
  const [sources, setSources] = useState<DataSource[]>([]);
  const [selectedYear, setSelectedYear] = useState<number | null>(null);
  const [selectedAsset, setSelectedAsset] = useState("");
  const [isLoading, setIsLoading] = useState(true);
  const [runningJob, setRunningJob] = useState<"" | "summary" | "prediction" | "all">("");
  const [analyticsError, setAnalyticsError] = useState("");
  const [analyticsMessage, setAnalyticsMessage] = useState("");

  useEffect(() => {
    let cancelled = false;

    async function load() {
      setIsLoading(true);
      const [
        nextJobs,
        nextSummaries,
        nextPredictions,
        nextInstruments,
        nextSources,
      ] = await Promise.all([
        loadSparkJobs(),
        loadYearlySummaries(),
        loadPricePredictions(),
        loadInstruments(),
        loadDataSources(),
      ]);

      if (!cancelled) {
        setJobs(nextJobs);
        setSummaries(sortSummaries(nextSummaries));
        setPredictions(sortPredictions(nextPredictions));
        setInstruments(nextInstruments);
        setSources(nextSources);
        setIsLoading(false);
      }
    }

    load();

    return () => {
      cancelled = true;
    };
  }, []);

  const years = useMemo(
    () => [...new Set(summaries.map((summary) => summary.businessYear))].sort((a, b) => b - a),
    [summaries],
  );

  const assetOptions = useMemo(() => {
    const options = new Map<string, { symbol: string; assetId: string; label: string }>();

    for (const instrument of instruments) {
      const symbol = instrument.symbol || lastSegment(instrument.id);
      options.set(symbol, {
        symbol,
        assetId: instrument.id,
        label: `${symbol} - ${instrument.dataSource || instrument.class}`,
      });
    }

    for (const summary of summaries) {
      const symbol = summary.assetSymbol || lastSegment(summary.assetId);
      if (!options.has(symbol)) {
        options.set(symbol, {
          symbol,
          assetId: summary.assetId,
          label: `${symbol} - ${lastSegment(summary.dataSourceId)}`,
        });
      }
    }

    for (const prediction of predictions) {
      const symbol = prediction.assetSymbol || lastSegment(prediction.assetId);
      if (!options.has(symbol)) {
        options.set(symbol, {
          symbol,
          assetId: prediction.assetId,
          label: `${symbol} - ${prediction.dataSourceId ? lastSegment(prediction.dataSourceId) : "analytics"}`,
        });
      }
    }

    return [...options.values()].sort((a, b) => a.symbol.localeCompare(b.symbol));
  }, [instruments, predictions, summaries]);

  useEffect(() => {
    if (!selectedAsset && assetOptions.length) {
      setSelectedAsset(assetOptions[0].symbol);
    }
  }, [assetOptions, selectedAsset]);

  const selectedAssetId = useMemo(
    () => assetOptions.find((asset) => asset.symbol === selectedAsset)?.assetId ?? "",
    [assetOptions, selectedAsset],
  );

  const filteredSummaries = useMemo(
    () => summaries.filter((summary) => {
      const symbol = summary.assetSymbol || lastSegment(summary.assetId);
      return (selectedYear === null || summary.businessYear === selectedYear)
        && (!selectedAsset || symbol === selectedAsset);
    }),
    [selectedAsset, selectedYear, summaries],
  );

  const assetSummary = useMemo(
    () => summaries
      .filter((summary) => (summary.assetSymbol || lastSegment(summary.assetId)) === selectedAsset)
      .sort((a, b) => a.businessYear - b.businessYear),
    [selectedAsset, summaries],
  );

  const selectedPredictions = useMemo(
    () => predictions.filter((prediction) => (prediction.assetSymbol || lastSegment(prediction.assetId)) === selectedAsset),
    [predictions, selectedAsset],
  );

  const historicalPredictions = selectedPredictions.filter((prediction) => prediction.actualClose !== null);
  const modelR2 = firstNumber(selectedPredictions.map((prediction) => prediction.modelR2));
  const modelRMSE = firstNumber(selectedPredictions.map((prediction) => prediction.modelRMSE));

  const metricCards = [
    { label: "Spark Jobs", value: jobs.length, hint: "/api/v1/analytics/jobs" },
    { label: "Yearly Summaries", value: summaries.length, hint: "analytics_yearly_summaries" },
    { label: "Predictions", value: predictions.length, hint: "analytics_price_predictions" },
    { label: "Warehouse Assets", value: instruments.length, hint: `${sources.length} data sources` },
  ];

  async function refreshAnalyticsData() {
    const [
      nextJobs,
      nextSummaries,
      nextPredictions,
      nextInstruments,
      nextSources,
    ] = await Promise.all([
      loadSparkJobs(),
      loadYearlySummaries(),
      loadPricePredictions(),
      loadInstruments(),
      loadDataSources(),
    ]);

    setJobs(nextJobs);
    setSummaries(sortSummaries(nextSummaries));
    setPredictions(sortPredictions(nextPredictions));
    setInstruments(nextInstruments);
    setSources(nextSources);
  }

  async function handleRunAnalytics(job: "summary" | "prediction" | "all") {
    if (!selectedAssetId) {
      setAnalyticsError("Select an instrument first.");
      return;
    }

    setAnalyticsError("");
    setAnalyticsMessage("");
    setRunningJob(job);

    try {
      if (job === "summary") {
        await runYearlySummaries(selectedAssetId);
        setAnalyticsMessage(`Yearly summary completed for ${selectedAsset}.`);
      } else if (job === "prediction") {
        await runPriceRegression(selectedAssetId);
        setAnalyticsMessage(`Price prediction completed for ${selectedAsset}.`);
      } else {
        await runAllAnalytics(selectedAssetId);
        setAnalyticsMessage(`Spark analytics completed for ${selectedAsset}.`);
      }

      await refreshAnalyticsData();
    } catch (error) {
      setAnalyticsError(error instanceof Error ? error.message : "Spark analytics job failed.");
    } finally {
      setRunningJob("");
    }
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-foreground">Spark Analytics</h1>
        <p className="mt-1 max-w-2xl text-sm text-muted-foreground">
          Analytics UI prepared for Spark aggregation and ML outputs persisted back into MongoDB.
        </p>
      </div>

      <div className="grid grid-cols-2 gap-4 xl:grid-cols-4">
        {metricCards.map((metric) => (
          <div key={metric.label} className="rounded-xl border border-border bg-card p-4">
            <span className="text-xs uppercase tracking-wider text-muted-foreground">{metric.label}</span>
            <p className="mt-1 font-mono text-2xl font-bold text-card-foreground">
              {isLoading ? "-" : metric.value.toLocaleString()}
            </p>
            <p className="mt-1 truncate text-xs text-muted-foreground">{metric.hint}</p>
          </div>
        ))}
      </div>

      <div className="rounded-xl border border-border bg-card p-5">
        <div className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
          <div>
            <h2 className="text-sm font-semibold text-card-foreground">Analysis Runner</h2>
            <p className="mt-1 text-xs text-muted-foreground">
              Select an instrument and run Spark aggregation or ML regression.
            </p>
          </div>
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
            <select
              value={selectedAsset}
              onChange={(event) => setSelectedAsset(event.target.value)}
              disabled={!assetOptions.length || Boolean(runningJob)}
              className="min-w-[220px] rounded-md border border-border bg-muted px-3 py-2 text-sm text-foreground disabled:opacity-60"
            >
              {!assetOptions.length && <option value="">No instruments</option>}
              {assetOptions.map((asset) => (
                <option key={asset.assetId} value={asset.symbol}>
                  {asset.label}
                </option>
              ))}
            </select>
            <button
              onClick={() => handleRunAnalytics("summary")}
              disabled={!selectedAssetId || Boolean(runningJob)}
              className="inline-flex items-center justify-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-opacity hover:opacity-90 disabled:opacity-60"
            >
              {runningJob === "summary" ? <RefreshCw className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}
              Yearly Summary
            </button>
            <button
              onClick={() => handleRunAnalytics("prediction")}
              disabled={!selectedAssetId || Boolean(runningJob)}
              className="inline-flex items-center justify-center gap-2 rounded-lg border border-border bg-muted px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-muted/70 disabled:opacity-60"
            >
              {runningJob === "prediction" ? <RefreshCw className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}
              Price Prediction
            </button>
            <button
              onClick={() => handleRunAnalytics("all")}
              disabled={!selectedAssetId || Boolean(runningJob)}
              className="inline-flex items-center justify-center gap-2 rounded-lg border border-primary/40 bg-primary/10 px-4 py-2 text-sm font-medium text-primary transition-colors hover:bg-primary/15 disabled:opacity-60"
            >
              {runningJob === "all" ? <RefreshCw className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}
              Run All
            </button>
          </div>
        </div>
        {(analyticsMessage || analyticsError) && (
          <p className={`mt-3 text-xs ${analyticsError ? "text-destructive" : "text-emerald-400"}`}>
            {analyticsError || analyticsMessage}
          </p>
        )}
      </div>

      <div className="rounded-xl border border-border bg-card p-5">
        <h2 className="mb-4 text-sm font-semibold text-card-foreground">Spark Job Status</h2>
        {jobs.length ? (
          <div className="grid gap-3">
            {jobs.map((job) => (
              <div key={job.jobId} className="flex flex-col gap-3 rounded-lg border border-border bg-muted/30 px-4 py-3 sm:flex-row sm:items-center">
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-mono text-sm font-semibold text-primary">{job.name}</span>
                    <StatusBadge status={job.status} />
                    <span className="rounded bg-muted px-2 py-0.5 text-xs text-muted-foreground">
                      {job.type === "aggregation" ? "UC-A Aggregation" : "UC-B ML Regression"}
                    </span>
                  </div>
                  <p className="mt-1 text-xs text-muted-foreground">
                    Output: <code className="text-accent/80">{job.outputCollection || "-"}</code>
                    {job.recordsProcessed !== null && job.recordsProcessed !== undefined && (
                      <> · {job.recordsProcessed.toLocaleString()} records</>
                    )}
                    {job.completedAt && <> · {new Date(job.completedAt).toLocaleString()}</>}
                  </p>
                  {job.error && <p className="mt-1 text-xs text-destructive">{job.error}</p>}
                </div>
              </div>
            ))}
          </div>
        ) : (
          <EmptyState
            title="No Spark job status returned"
            description="The UI is ready for /api/v1/analytics/jobs after the Spark module writes job metadata."
          />
        )}
      </div>

      <div className="rounded-xl border border-border bg-card p-5">
        <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h2 className="text-sm font-semibold text-card-foreground">UC-A: Yearly Summaries</h2>
            <p className="mt-0.5 text-xs text-muted-foreground">
              GET /api/v1/analytics/yearly-summaries from analytics_yearly_summaries
            </p>
          </div>
          <select
            value={selectedYear ?? ""}
            onChange={(event) => setSelectedYear(event.target.value ? Number(event.target.value) : null)}
            disabled={!years.length}
            className="rounded-md border border-border bg-muted px-2 py-1 text-xs text-foreground disabled:opacity-60"
          >
            <option value="">{years.length ? "All years" : "No years"}</option>
            {years.map((year) => (
              <option key={year} value={year}>
                {year}
              </option>
            ))}
          </select>
        </div>

        {filteredSummaries.length ? (
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b border-border text-muted-foreground">
                  <th className="px-3 py-2 text-left font-medium">Asset</th>
                  <th className="px-3 py-2 text-left font-medium">Source</th>
                  <th className="px-3 py-2 text-right font-medium">Count</th>
                  <th className="px-3 py-2 text-right font-medium">Min Close</th>
                  <th className="px-3 py-2 text-right font-medium">Max Close</th>
                  <th className="px-3 py-2 text-right font-medium">Avg Close</th>
                  <th className="px-3 py-2 text-right font-medium">Avg Volume</th>
                </tr>
              </thead>
              <tbody>
                {filteredSummaries.map((summary) => {
                  const symbol = summary.assetSymbol || lastSegment(summary.assetId);
                  return (
                    <tr
                      key={`${summary.assetId}-${summary.dataSourceId}-${summary.businessYear}`}
                      className={`cursor-pointer border-b border-border/50 transition-colors hover:bg-muted/30 ${symbol === selectedAsset ? "bg-muted/40" : ""}`}
                      onClick={() => setSelectedAsset(symbol)}
                    >
                      <td className="px-3 py-2 font-mono font-semibold text-primary">{symbol}</td>
                      <td className="max-w-[220px] truncate px-3 py-2 text-muted-foreground">{summary.dataSourceId}</td>
                      <td className="px-3 py-2 text-right font-mono text-card-foreground">{summary.count.toLocaleString()}</td>
                      <td className="px-3 py-2 text-right font-mono text-card-foreground">{formatMoney(summary.minClose)}</td>
                      <td className="px-3 py-2 text-right font-mono text-card-foreground">{formatMoney(summary.maxClose)}</td>
                      <td className="px-3 py-2 text-right font-mono text-accent">{formatMoney(summary.avgClose)}</td>
                      <td className="px-3 py-2 text-right font-mono text-muted-foreground">{formatCompact(summary.avgVolume)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        ) : (
          <EmptyState
            title="No yearly summaries returned"
            description="Run the Spark aggregation job so MongoDB contains analytics_yearly_summaries."
          />
        )}
      </div>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
        <div className="rounded-xl border border-border bg-card p-5">
          <h2 className="text-sm font-semibold text-card-foreground">
            Avg Close Trend {selectedAsset && <span className="text-primary">- {selectedAsset}</span>}
          </h2>
          <p className="mb-4 mt-0.5 text-xs text-muted-foreground">avgClose by businessYear</p>
          {assetSummary.length ? (
            <ResponsiveContainer width="100%" height={240}>
              <BarChart data={assetSummary}>
                <XAxis dataKey="businessYear" tick={AXIS_TICK} axisLine={false} tickLine={false} />
                <YAxis tick={AXIS_TICK} axisLine={false} tickLine={false} tickFormatter={(value) => `$${value}`} />
                <Tooltip contentStyle={TOOLTIP_STYLE} />
                <Bar dataKey="avgClose" name="Avg Close" fill="hsl(25, 100%, 50%)" radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          ) : (
            <EmptyState title="No trend data returned" />
          )}
        </div>

        <div className="rounded-xl border border-border bg-card p-5">
          <h2 className="text-sm font-semibold text-card-foreground">Min / Max Close Range</h2>
          <p className="mb-4 mt-0.5 text-xs text-muted-foreground">Spread per asset for selected year</p>
          {filteredSummaries.length ? (
            <ResponsiveContainer width="100%" height={240}>
              <BarChart data={filteredSummaries} layout="vertical">
                <XAxis type="number" tick={AXIS_TICK} axisLine={false} tickLine={false} tickFormatter={(value) => `$${value}`} />
                <YAxis type="category" dataKey="assetSymbol" tick={AXIS_TICK} axisLine={false} tickLine={false} width={70} />
                <Tooltip contentStyle={TOOLTIP_STYLE} />
                <Bar dataKey="minClose" name="Min Close" fill="hsl(0, 85%, 50%)" radius={[4, 0, 0, 4]} />
                <Bar dataKey="maxClose" name="Max Close" fill="hsl(160, 84%, 39%)" radius={[0, 4, 4, 0]} />
              </BarChart>
            </ResponsiveContainer>
          ) : (
            <EmptyState title="No range data returned" />
          )}
        </div>
      </div>

      <div className="rounded-xl border border-border bg-card p-5">
        <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h2 className="text-sm font-semibold text-card-foreground">
              UC-B: Price Prediction {selectedAsset && <span className="text-primary">- {selectedAsset}</span>}
            </h2>
            <p className="mt-0.5 text-xs text-muted-foreground">
              GET /api/v1/analytics/predictions from analytics_price_predictions
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
            <select
              value={selectedAsset}
              onChange={(event) => setSelectedAsset(event.target.value)}
              disabled={!assetOptions.length}
              className="rounded-md border border-border bg-muted px-2 py-1 text-xs text-foreground disabled:opacity-60"
            >
              {!assetOptions.length && <option value="">No assets</option>}
              {assetOptions.map((asset) => (
                <option key={asset.assetId} value={asset.symbol}>
                  {asset.symbol}
                </option>
              ))}
            </select>
            <div className="rounded-md border border-border bg-muted px-2.5 py-1 text-xs">
              <span className="text-muted-foreground">R2 </span>
              <span className="font-mono font-bold text-accent">{modelR2 === null ? "-" : modelR2.toFixed(2)}</span>
            </div>
            <div className="rounded-md border border-border bg-muted px-2.5 py-1 text-xs">
              <span className="text-muted-foreground">RMSE </span>
              <span className="font-mono font-bold text-accent">{modelRMSE === null ? "-" : formatMoney(modelRMSE)}</span>
            </div>
          </div>
        </div>

        {selectedPredictions.length ? (
          <ResponsiveContainer width="100%" height={300}>
            <ComposedChart data={selectedPredictions}>
              <XAxis dataKey="date" tick={AXIS_TICK} axisLine={false} tickLine={false} tickFormatter={(value) => String(value).slice(5)} />
              <YAxis tick={AXIS_TICK} axisLine={false} tickLine={false} domain={["auto", "auto"]} tickFormatter={(value) => `$${value}`} />
              <Tooltip contentStyle={TOOLTIP_STYLE} />
              <Legend wrapperStyle={{ fontSize: 11, color: "hsl(0, 0%, 60%)" }} />
              <Line type="monotone" dataKey="actualClose" name="Actual Close" stroke="hsl(0, 0%, 60%)" strokeWidth={2} dot={false} connectNulls={false} />
              <Line type="monotone" dataKey="predictedClose" name="Predicted Close" stroke="hsl(25, 100%, 50%)" strokeWidth={2} strokeDasharray="6 3" dot={false} />
            </ComposedChart>
          </ResponsiveContainer>
        ) : (
          <EmptyState
            title="No ML predictions returned"
            description="Run the Spark ML regression job so MongoDB contains analytics_price_predictions."
          />
        )}
      </div>

      <div className="rounded-xl border border-border bg-card p-5">
        <h2 className="text-sm font-semibold text-card-foreground">Residual Analysis</h2>
        <p className="mb-4 mt-0.5 text-xs text-muted-foreground">actualClose minus predictedClose for rows with known actuals</p>
        {historicalPredictions.length ? (
          <ResponsiveContainer width="100%" height={190}>
            <BarChart data={historicalPredictions}>
              <XAxis dataKey="date" tick={AXIS_TICK} axisLine={false} tickLine={false} tickFormatter={(value) => String(value).slice(8)} />
              <YAxis tick={AXIS_TICK} axisLine={false} tickLine={false} />
              <Tooltip contentStyle={TOOLTIP_STYLE} />
              <Bar dataKey="residual" name="Residual" fill="hsl(25, 100%, 50%)" radius={[2, 2, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        ) : (
          <EmptyState title="No residual rows returned" />
        )}
      </div>

      <div className="rounded-xl border border-border bg-card p-5">
        <h2 className="mb-3 text-sm font-semibold text-card-foreground">Backend API Contract</h2>
        <div className="space-y-2">
          {[
            { method: "GET", path: "/api/v1/analytics/jobs", desc: "Returns Spark job metadata and output collections." },
            { method: "GET", path: "/api/v1/analytics/yearly-summaries", desc: "Returns aggregation results from analytics_yearly_summaries." },
            { method: "GET", path: "/api/v1/analytics/predictions", desc: "Returns ML prediction rows from analytics_price_predictions." },
            { method: "POST", path: "/api/v1/analytics/run/yearly-summaries", desc: "Runs the Spark aggregation job for the selected asset." },
            { method: "POST", path: "/api/v1/analytics/run/price-regression", desc: "Runs the Spark ML regression job for the selected asset." },
            { method: "POST", path: "/api/v1/analytics/run/all", desc: "Runs aggregation and ML regression for the selected asset." },
          ].map((endpoint) => (
            <div key={endpoint.path} className="flex items-start gap-3 rounded-lg border border-border bg-muted/30 px-3 py-2">
              <span className={`rounded px-2 py-0.5 font-mono text-xs font-bold ${endpoint.method === "GET" ? "bg-emerald-500/15 text-emerald-400" : "bg-primary/15 text-primary"}`}>
                {endpoint.method}
              </span>
              <div className="min-w-0">
                <code className="text-xs text-accent">{endpoint.path}</code>
                <p className="mt-0.5 text-xs text-muted-foreground">{endpoint.desc}</p>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function sortSummaries(items: YearlySummary[]) {
  return [...items].sort((a, b) => {
    const assetCompare = (a.assetSymbol || a.assetId).localeCompare(b.assetSymbol || b.assetId);
    if (assetCompare !== 0) {
      return assetCompare;
    }
    return a.businessYear - b.businessYear;
  });
}

function sortPredictions(items: PricePrediction[]) {
  return [...items].sort((a, b) => {
    const assetCompare = (a.assetSymbol || a.assetId).localeCompare(b.assetSymbol || b.assetId);
    if (assetCompare !== 0) {
      return assetCompare;
    }
    return a.date.localeCompare(b.date);
  });
}

function lastSegment(value: string) {
  return String(value).split("/").filter(Boolean).pop() ?? value;
}

function firstNumber(values: Array<number | null | undefined>) {
  for (const value of values) {
    if (typeof value === "number" && Number.isFinite(value)) {
      return value;
    }
  }
  return null;
}

function formatMoney(value: number | null | undefined) {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    return "-";
  }
  return `$${value.toLocaleString(undefined, { maximumFractionDigits: 2 })}`;
}

function formatCompact(value: number | null | undefined) {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    return "-";
  }
  return Intl.NumberFormat(undefined, {
    notation: "compact",
    maximumFractionDigits: 1,
  }).format(value);
}
