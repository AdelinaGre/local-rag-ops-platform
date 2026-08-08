import { useEffect, useState } from "react";
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import EmptyState from "@/components/EmptyState";
import type { DataSource, FinancialInstrument, TimeSeriesPoint } from "@/types/warehouse";
import { loadDataSources, loadInstruments, loadTimeSeries } from "@/lib/warehouseApi";

const periods = ["7D", "30D", "90D"];

export default function TimeSeries() {
  const [selectedAssetId, setSelectedAssetId] = useState("");
  const [selectedSourceId, setSelectedSourceId] = useState("");
  const [period, setPeriod] = useState("30D");
  const [instruments, setInstruments] = useState<FinancialInstrument[]>([]);
  const [sources, setSources] = useState<DataSource[]>([]);
  const [data, setData] = useState<TimeSeriesPoint[]>([]);

  useEffect(() => {
    let cancelled = false;

    async function loadLookups() {
      const [nextInstruments, nextSources] = await Promise.all([
        loadInstruments(),
        loadDataSources(),
      ]);

      if (!cancelled) {
        setInstruments(nextInstruments);
        setSources(nextSources);
        setSelectedAssetId(nextInstruments[0]?.id ?? "");
        setSelectedSourceId(nextSources[0]?.id ?? "");
      }
    }

    loadLookups();

    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    const days = period === "7D" ? 7 : period === "30D" ? 30 : 90;

    loadTimeSeries(selectedAssetId, selectedSourceId, days).then((rows) => {
      if (!cancelled) {
        setData(rows);
      }
    });

    return () => {
      cancelled = true;
    };
  }, [period, selectedAssetId, selectedSourceId]);

  const lastPoint = data[data.length - 1];

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-foreground">Time Series Explorer</h1>
        <p className="text-sm text-muted-foreground mt-1">
          Historical values returned by the warehouse API
        </p>
      </div>

      <div className="flex flex-col sm:flex-row gap-3">
        <select
          value={selectedAssetId}
          onChange={(event) => setSelectedAssetId(event.target.value)}
          disabled={!instruments.length}
          className="px-4 py-2.5 rounded-lg border border-border bg-card text-sm text-card-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-60"
        >
          {!instruments.length && <option value="">No instruments</option>}
          {instruments.map((instrument) => (
            <option key={instrument.id} value={instrument.id}>
              {instrument.symbol} - {instrument.name}
            </option>
          ))}
        </select>
        <select
          value={selectedSourceId}
          onChange={(event) => setSelectedSourceId(event.target.value)}
          disabled={!sources.length}
          className="px-4 py-2.5 rounded-lg border border-border bg-card text-sm text-card-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-60"
        >
          {!sources.length && <option value="">No sources</option>}
          {sources.map((source) => (
            <option key={source.id} value={source.id}>
              {source.name}
            </option>
          ))}
        </select>
        <div className="flex gap-1.5">
          {periods.map((item) => (
            <button
              key={item}
              onClick={() => setPeriod(item)}
              className={`px-3 py-2 rounded-lg text-xs font-medium transition-colors ${
                period === item
                  ? "bg-primary text-primary-foreground"
                  : "bg-secondary text-secondary-foreground hover:bg-accent hover:text-accent-foreground"
              }`}
            >
              {item}
            </button>
          ))}
        </div>
      </div>

      <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
        {[
          { label: "Open", value: lastPoint?.open },
          { label: "Close", value: lastPoint?.close },
          { label: "High", value: lastPoint?.high },
          { label: "Low", value: lastPoint?.low },
        ].map((stat) => (
          <div key={stat.label} className="rounded-xl border border-border bg-card p-4">
            <span className="text-xs text-muted-foreground uppercase tracking-wider">{stat.label}</span>
            <p className="text-lg font-bold font-mono text-card-foreground mt-1">
              {stat.value === undefined ? "-" : `$${stat.value.toFixed(2)}`}
            </p>
          </div>
        ))}
      </div>

      <div className="rounded-xl border border-border bg-card p-5">
        <h2 className="text-sm font-semibold text-card-foreground mb-4">Price</h2>
        {data.length ? (
          <ResponsiveContainer width="100%" height={300}>
            <AreaChart data={data}>
              <defs>
                <linearGradient id="priceGrad" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor="hsl(25, 100%, 50%)" stopOpacity={0.3} />
                  <stop offset="95%" stopColor="hsl(25, 100%, 50%)" stopOpacity={0} />
                </linearGradient>
              </defs>
              <XAxis dataKey="date" tick={{ fontSize: 10, fill: "hsl(0, 0%, 45%)" }} axisLine={false} tickLine={false} tickFormatter={(value) => String(value).slice(5)} />
              <YAxis tick={{ fontSize: 10, fill: "hsl(0, 0%, 45%)" }} axisLine={false} tickLine={false} domain={["auto", "auto"]} />
              <Tooltip contentStyle={{ backgroundColor: "hsl(0, 0%, 6%)", border: "1px solid hsl(0, 0%, 14%)", borderRadius: "8px", fontSize: 12, color: "hsl(0, 0%, 95%)" }} />
              <Area type="monotone" dataKey="close" stroke="hsl(25, 100%, 50%)" fill="url(#priceGrad)" strokeWidth={2} />
            </AreaChart>
          </ResponsiveContainer>
        ) : (
          <EmptyState title="No price rows returned" />
        )}
      </div>

      <div className="rounded-xl border border-border bg-card p-5">
        <h2 className="text-sm font-semibold text-card-foreground mb-4">Volume</h2>
        {data.length ? (
          <ResponsiveContainer width="100%" height={160}>
            <BarChart data={data}>
              <XAxis dataKey="date" tick={{ fontSize: 10, fill: "hsl(0, 0%, 45%)" }} axisLine={false} tickLine={false} tickFormatter={(value) => String(value).slice(5)} />
              <YAxis tick={{ fontSize: 10, fill: "hsl(0, 0%, 45%)" }} axisLine={false} tickLine={false} tickFormatter={(value) => `${(Number(value) / 1e6).toFixed(0)}M`} />
              <Tooltip contentStyle={{ backgroundColor: "hsl(0, 0%, 6%)", border: "1px solid hsl(0, 0%, 14%)", borderRadius: "8px", fontSize: 12, color: "hsl(0, 0%, 95%)" }} />
              <Bar dataKey="volume" fill="hsl(0, 0%, 16%)" radius={[3, 3, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        ) : (
          <EmptyState title="No volume rows returned" />
        )}
      </div>
    </div>
  );
}
