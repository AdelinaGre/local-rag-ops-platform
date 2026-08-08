import { useState } from "react";
import { NavLink, useLocation } from "react-router-dom";
import {
  LayoutDashboard,
  Database,
  LineChart,
  Download,
  BarChart3,
  MessageSquare,
  ChevronLeft,
  ChevronRight,
} from "lucide-react";

const navItems = [
  { to: "/", icon: LayoutDashboard, label: "Dashboard" },
  { to: "/instruments", icon: Database, label: "Instruments" },
  { to: "/timeseries", icon: LineChart, label: "Time Series" },
  { to: "/ingestion", icon: Download, label: "Data Ingestion" },
  { to: "/analytics", icon: BarChart3, label: "Analytics" },
  { to: "/assistant", icon: MessageSquare, label: "LLM Assistant" },
];

export default function AppSidebar() {
  const [collapsed, setCollapsed] = useState(false);
  const location = useLocation();

  return (
    <aside
      className={`flex flex-col border-r border-border bg-sidebar transition-all duration-300 ${
        collapsed ? "w-16" : "w-60"
      }`}
    >
      <div className="flex items-center gap-2 px-4 py-5 border-b border-border">
        <div className="h-8 w-8 rounded-lg bg-primary flex items-center justify-center shrink-0">
          <span className="text-primary-foreground font-bold text-sm">F</span>
        </div>
        {!collapsed && (
          <span className="text-foreground font-semibold text-sm tracking-wide">
            Market DWH
          </span>
        )}
      </div>

      <nav className="flex-1 py-4 space-y-1 px-2">
        {navItems.map((item) => {
          const active = location.pathname === item.to;
          return (
            <NavLink
              key={item.to}
              to={item.to}
              className={`flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors ${
                active
                  ? "bg-primary/10 text-primary"
                  : "text-sidebar-foreground hover:bg-sidebar-accent hover:text-sidebar-accent-foreground"
              }`}
            >
              <item.icon className="h-4 w-4 shrink-0" />
              {!collapsed && <span>{item.label}</span>}
            </NavLink>
          );
        })}
      </nav>

      <button
        onClick={() => setCollapsed(!collapsed)}
        className="flex items-center justify-center py-3 border-t border-border text-muted-foreground hover:text-foreground transition-colors"
      >
        {collapsed ? <ChevronRight className="h-4 w-4" /> : <ChevronLeft className="h-4 w-4" />}
      </button>
    </aside>
  );
}
