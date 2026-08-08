import { LucideIcon } from "lucide-react";

interface StatCardProps {
  title: string;
  value: string;
  subtitle?: string;
  icon: LucideIcon;
  trend?: "up" | "down" | "neutral";
}

export default function StatCard({ title, value, subtitle, icon: Icon, trend }: StatCardProps) {
  return (
    <div className="rounded-xl border border-border bg-card p-5">
      <div className="flex items-center justify-between mb-3">
        <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">
          {title}
        </span>
        <Icon className="h-4 w-4 text-muted-foreground" />
      </div>
      <div className="text-2xl font-bold text-card-foreground">{value}</div>
      {subtitle && (
        <p
          className={`text-xs mt-1 font-medium ${
            trend === "up"
              ? "text-chart-up"
              : trend === "down"
              ? "text-chart-down"
              : "text-muted-foreground"
          }`}
        >
          {subtitle}
        </p>
      )}
    </div>
  );
}