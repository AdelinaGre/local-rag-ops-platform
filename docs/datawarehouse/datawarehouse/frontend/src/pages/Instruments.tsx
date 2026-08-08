import { useEffect, useMemo, useState } from "react";
import { Search } from "lucide-react";
import EmptyState from "@/components/EmptyState";
import type { FinancialInstrument } from "@/types/warehouse";
import { loadInstruments } from "@/lib/warehouseApi";

export default function Instruments() {
  const [search, setSearch] = useState("");
  const [selectedClass, setSelectedClass] = useState("All");
  const [instruments, setInstruments] = useState<FinancialInstrument[]>([]);

  useEffect(() => {
    let cancelled = false;
    loadInstruments().then((items) => {
      if (!cancelled) {
        setInstruments(items);
      }
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const classes = useMemo(
    () => ["All", ...Array.from(new Set(instruments.map((item) => item.class))).sort()],
    [instruments],
  );

  const filtered = instruments.filter(
    (item) =>
      (selectedClass === "All" || item.class === selectedClass) &&
      (item.symbol.toLowerCase().includes(search.toLowerCase()) ||
        item.name.toLowerCase().includes(search.toLowerCase()) ||
        item.id.toLowerCase().includes(search.toLowerCase())),
  );

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-foreground">Financial Instruments</h1>
        <p className="text-sm text-muted-foreground mt-1">
          Instruments returned by the warehouse API
        </p>
      </div>

      <div className="flex flex-col sm:flex-row gap-3">
        <div className="relative flex-1">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
          <input
            type="text"
            placeholder="Search by symbol, name, or id..."
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            className="w-full pl-10 pr-4 py-2.5 rounded-lg border border-border bg-card text-sm text-card-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          />
        </div>
        <div className="flex gap-1.5 flex-wrap">
          {classes.map((assetClass) => (
            <button
              key={assetClass}
              onClick={() => setSelectedClass(assetClass)}
              className={`px-3 py-2 rounded-lg text-xs font-medium transition-colors ${
                selectedClass === assetClass
                  ? "bg-primary text-primary-foreground"
                  : "bg-secondary text-secondary-foreground hover:bg-accent hover:text-accent-foreground"
              }`}
            >
              {assetClass}
            </button>
          ))}
        </div>
      </div>

      <div className="rounded-xl border border-border bg-card overflow-hidden">
        {filtered.length ? (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border">
                <th className="text-left px-4 py-3 text-xs font-semibold uppercase tracking-wider text-muted-foreground">Symbol</th>
                <th className="text-left px-4 py-3 text-xs font-semibold uppercase tracking-wider text-muted-foreground">Name</th>
                <th className="text-left px-4 py-3 text-xs font-semibold uppercase tracking-wider text-muted-foreground">Class</th>
                <th className="text-left px-4 py-3 text-xs font-semibold uppercase tracking-wider text-muted-foreground">Region</th>
                <th className="text-left px-4 py-3 text-xs font-semibold uppercase tracking-wider text-muted-foreground">Source</th>
                <th className="text-left px-4 py-3 text-xs font-semibold uppercase tracking-wider text-muted-foreground">Asset ID</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((instrument) => (
                <tr key={instrument.id} className="border-b border-border last:border-0 hover:bg-muted/50 transition-colors">
                  <td className="px-4 py-3 font-mono font-semibold text-primary">{instrument.symbol}</td>
                  <td className="px-4 py-3 text-card-foreground">{instrument.name}</td>
                  <td className="px-4 py-3">
                    <span className="px-2 py-0.5 rounded-md text-xs font-medium bg-secondary text-secondary-foreground">
                      {instrument.class}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-muted-foreground">{instrument.region || "-"}</td>
                  <td className="px-4 py-3 text-muted-foreground">{instrument.dataSource || "-"}</td>
                  <td className="px-4 py-3 font-mono text-xs text-muted-foreground">{instrument.id}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <div className="p-5">
            <EmptyState
              title="No instruments returned"
              description="Run ingestion or check that MongoDB contains assets with the current append-only schema."
            />
          </div>
        )}
      </div>
    </div>
  );
}
