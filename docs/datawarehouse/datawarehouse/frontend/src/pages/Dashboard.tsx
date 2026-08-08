import { useEffect, useMemo, useState } from "react";
import { Activity, Clock3, Database, Globe, LineChart } from "lucide-react";
import {
  Area,
  AreaChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import EmptyState from "@/components/EmptyState";
import StatCard from "@/components/StatCard";
import type { DataSource, FinancialInstrument, TimeSeriesPoint } from "@/types/warehouse";
import { loadDataSources, loadInstruments, loadTimeSeries } from "@/lib/warehouseApi";

export default function Dashboard() {
  const [instruments, setInstruments] = useState<FinancialInstrument[]>([]);
  const [sources, setSources] = useState<DataSource[]>([]);
  const [timeSeries, setTimeSeries] = useState<TimeSeriesPoint[]>([]);

  useEffect(() => {
    let cancelled = false;

    async function load() {
      const [nextInstruments, nextSources] = await Promise.all([
        loadInstruments(),
        loadDataSources(),
      ]);
      const nextTimeSeries = await loadTimeSeries(nextInstruments[0]?.id, nextSources[0]?.id, 30);

      if (!cancelled) {
        setInstruments(nextInstruments);
        setSources(nextSources);
        setTimeSeries(nextTimeSeries);
      }
    }

    load();

    return () => {
      cancelled = true;
    };
  }, []);

  const latestSync = useMemo(() => {
    const dates = sources
      .map((source) => source.lastSync)
      .filter(Boolean)
      .map((value) => new Date(value as string))
      .filter((date) => Number.isFinite(date.getTime()))
      .sort((a, b) => b.getTime() - a.getTime());

    return dates[0];
  }, [sources]);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-foreground">Dashboard</h1>
        <p className="text-sm text-muted-foreground mt-1">
          Financial Market Data Warehouse
        </p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        <StatCard
          title="Stored Instruments"
          value={instruments.length.toLocaleString()}
          subtitle="Returned by /api/v1/assets"
          icon={Database}
          trend="neutral"
        />
        <StatCard
          title="Data Sources"
          value={sources.length.toLocaleString()}
          subtitle="Returned by /api/v1/data-sources"
          icon={Globe}
          trend="neutral"
        />
        <StatCard
          title="Series Points"
          value={timeSeries.length.toLocaleString()}
          subtitle="Current query window"
          icon={LineChart}
          trend="neutral"
        />
        <StatCard
          title="Latest Sync"
          value={latestSync ? latestSync.toLocaleDateString() : "-"}
          subtitle={latestSync ? latestSync.toLocaleTimeString() : "No source sync date"}
          icon={Clock3}
          trend="neutral"
        />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        <div className="lg:col-span-2 rounded-xl border border-border bg-card p-5">
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-sm font-semibold text-card-foreground">
              Latest Time Series Query
            </h2>
            <Activity className="h-4 w-4 text-muted-foreground" />
          </div>
          {timeSeries.length ? (
            <ResponsiveContainer width="100%" height={260}>
              <AreaChart data={timeSeries}>
                <defs>
                  <linearGradient id="colorClose" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="hsl(25, 100%, 50%)" stopOpacity={0.3} />
                    <stop offset="95%" stopColor="hsl(25, 100%, 50%)" stopOpacity={0} />
                  </linearGradient>
                </defs>
                <XAxis
                  dataKey="date"
                  tick={{ fontSize: 10, fill: "hsl(0, 0%, 45%)" }}
                  axisLine={false}
                  tickLine={false}
                  tickFormatter={(value) => String(value).slice(5)}
                />
                <YAxis
                  tick={{ fontSize: 10, fill: "hsl(0, 0%, 45%)" }}
                  axisLine={false}
                  tickLine={false}
                  domain={["auto", "auto"]}
                />
                <Tooltip
                  contentStyle={{
                    backgroundColor: "hsl(0, 0%, 6%)",
                    border: "1px solid hsl(0, 0%, 14%)",
                    borderRadius: "8px",
                    fontSize: 12,
                    color: "hsl(0, 0%, 95%)",
                  }}
                />
                <Area
                  type="monotone"
                  dataKey="close"
                  stroke="hsl(25, 100%, 50%)"
                  fill="url(#colorClose)"
                  strokeWidth={2}
                />
              </AreaChart>
            </ResponsiveContainer>
          ) : (
            <EmptyState
              title="No time series returned"
              description="Run ingestion and make sure the selected asset/source pair has records in MongoDB."
            />
          )}
        </div>

        <div className="rounded-xl border border-border bg-card p-5">
          <h2 className="text-sm font-semibold text-card-foreground mb-4">
            Loaded Instruments
          </h2>
          {instruments.length ? (
            <div className="space-y-3">
              {instruments.slice(0, 6).map((instrument) => (
                <div
                  key={instrument.id}
                  className="flex items-center justify-between gap-3 py-2 border-b border-border last:border-0"
                >
                  <div className="min-w-0">
                    <span className="block truncate text-sm font-semibold text-card-foreground">
                      {instrument.symbol}
                    </span>
                    <p className="truncate text-xs text-muted-foreground">{instrument.name}</p>
                  </div>
                  <span className="shrink-0 rounded-md bg-secondary px-2 py-0.5 text-xs font-medium text-secondary-foreground">
                    {instrument.class}
                  </span>
                </div>
              ))}
            </div>
          ) : (
            <EmptyState title="No instruments loaded" />
          )}
        </div>
      </div>
    </div>
  );
}
