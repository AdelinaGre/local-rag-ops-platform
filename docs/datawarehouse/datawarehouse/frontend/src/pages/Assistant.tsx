import { useEffect, useState } from "react";
import { Bot, Database, Globe, Loader2, Send, Sparkles, Wrench } from "lucide-react";
import EmptyState from "@/components/EmptyState";
import type { AssistantChatResponse, DataSource, FinancialInstrument } from "@/types/warehouse";
import { chatWithAssistant, loadDataSources, loadInstruments } from "@/lib/warehouseApi";

type ChatMessage = {
  role: "user" | "assistant";
  content: string;
};

export default function Assistant() {
  const [instruments, setInstruments] = useState<FinancialInstrument[]>([]);
  const [sources, setSources] = useState<DataSource[]>([]);
  const [selectedAssetId, setSelectedAssetId] = useState("");
  const [selectedDataSourceId, setSelectedDataSourceId] = useState("");
  const [startBusinessDate, setStartBusinessDate] = useState(defaultStartDate());
  const [endBusinessDate, setEndBusinessDate] = useState(defaultEndDate());
  const [prompt, setPrompt] = useState("Analyze the selected asset trend using warehouse records.");
  const [messages, setMessages] = useState<ChatMessage[]>([
    {
      role: "assistant",
      content: "Select an asset and date interval, then ask a question. I will run the warehouse MCP-style read workflow and summarize only returned data.",
    },
  ]);
  const [lastResponse, setLastResponse] = useState<AssistantChatResponse | null>(null);
  const [isSending, setIsSending] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    let cancelled = false;

    async function load() {
      const [nextInstruments, nextSources] = await Promise.all([
        loadInstruments(),
        loadDataSources(),
      ]);

      if (!cancelled) {
        setInstruments(nextInstruments);
        setSources(nextSources);
        const firstAsset = nextInstruments[0]?.id ?? "";
        setSelectedAssetId((current) => current || firstAsset);
        setSelectedDataSourceId((current) => current || inferSourceFromAsset(firstAsset, nextSources));
      }
    }

    load();

    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!selectedAssetId || selectedDataSourceId) {
      return;
    }
    setSelectedDataSourceId(inferSourceFromAsset(selectedAssetId, sources));
  }, [selectedAssetId, selectedDataSourceId, sources]);

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    const text = prompt.trim();
    if (!text || isSending) {
      return;
    }

    setError("");
    setIsSending(true);
    setMessages((current) => [...current, { role: "user", content: text }]);

    try {
      const response = await chatWithAssistant({
        message: text,
        assetId: selectedAssetId || undefined,
        dataSourceId: selectedDataSourceId || undefined,
        startBusinessDate,
        endBusinessDate,
        includeAttributes: true,
      });

      setLastResponse(response);
      setMessages((current) => [...current, { role: "assistant", content: response.answer }]);
      setPrompt("");
    } catch (nextError) {
      const message = nextError instanceof Error ? nextError.message : "Assistant request failed.";
      setError(message);
      setMessages((current) => [...current, { role: "assistant", content: `Request failed: ${message}` }]);
    } finally {
      setIsSending(false);
    }
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-foreground">LLM / Agentic AI Consumer</h1>
        <p className="text-sm text-muted-foreground mt-1">
          Ask questions through an LLM-style agent that chains warehouse read tools.
        </p>
      </div>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-[1fr_340px]">
        <div className="rounded-xl border border-border bg-card p-5">
          <div className="mb-4 flex items-center justify-between gap-3">
            <div className="flex items-center gap-2">
              <Bot className="h-4 w-4 text-primary" />
              <h2 className="text-sm font-semibold text-card-foreground">Assistant Chat</h2>
            </div>
            <span className="rounded-md bg-muted px-2 py-1 text-xs text-muted-foreground">
              {lastResponse?.llmUsed ? `LLM: ${lastResponse.model}` : "Fallback ready"}
            </span>
          </div>

          <div className="mb-4 grid gap-3 md:grid-cols-2 xl:grid-cols-4">
            <label className="space-y-1 text-xs text-muted-foreground">
              <span>Asset</span>
              <select
                value={selectedAssetId}
                onChange={(event) => {
                  const nextAssetId = event.target.value;
                  setSelectedAssetId(nextAssetId);
                  setSelectedDataSourceId(inferSourceFromAsset(nextAssetId, sources));
                }}
                className="w-full rounded-md border border-border bg-muted px-3 py-2 text-sm text-foreground"
              >
                {instruments.map((instrument) => (
                  <option key={instrument.id} value={instrument.id}>
                    {instrument.symbol} - {instrument.id}
                  </option>
                ))}
              </select>
            </label>

            <label className="space-y-1 text-xs text-muted-foreground">
              <span>Data Source</span>
              <select
                value={selectedDataSourceId}
                onChange={(event) => setSelectedDataSourceId(event.target.value)}
                className="w-full rounded-md border border-border bg-muted px-3 py-2 text-sm text-foreground"
              >
                {sources.map((source) => (
                  <option key={source.id} value={source.id}>
                    {source.id}
                  </option>
                ))}
              </select>
            </label>

            <label className="space-y-1 text-xs text-muted-foreground">
              <span>Start</span>
              <input
                type="date"
                value={startBusinessDate}
                onChange={(event) => setStartBusinessDate(event.target.value)}
                className="w-full rounded-md border border-border bg-muted px-3 py-2 text-sm text-foreground"
              />
            </label>

            <label className="space-y-1 text-xs text-muted-foreground">
              <span>End</span>
              <input
                type="date"
                value={endBusinessDate}
                onChange={(event) => setEndBusinessDate(event.target.value)}
                className="w-full rounded-md border border-border bg-muted px-3 py-2 text-sm text-foreground"
              />
            </label>
          </div>

          <div className="h-[390px] overflow-y-auto rounded-lg border border-border bg-muted/20 p-4">
            <div className="space-y-3">
              {messages.map((message, index) => (
                <div
                  key={`${message.role}-${index}`}
                  className={`max-w-[88%] rounded-lg px-3 py-2 text-sm ${
                    message.role === "user"
                      ? "ml-auto bg-primary text-primary-foreground"
                      : "bg-card text-card-foreground"
                  }`}
                >
                  <p className="whitespace-pre-wrap leading-relaxed">{message.content}</p>
                </div>
              ))}
              {isSending && (
                <div className="inline-flex items-center gap-2 rounded-lg bg-card px-3 py-2 text-sm text-muted-foreground">
                  <Loader2 className="h-4 w-4 animate-spin" />
                  Running warehouse tools and preparing answer
                </div>
              )}
            </div>
          </div>

          <form onSubmit={handleSubmit} className="mt-4 space-y-3">
            <textarea
              value={prompt}
              onChange={(event) => setPrompt(event.target.value)}
              placeholder="Example: Summarize BTCUSDT trend and mention data provenance."
              rows={3}
              className="w-full resize-none rounded-lg border border-border bg-muted px-3 py-2 text-sm text-foreground outline-none transition-colors placeholder:text-muted-foreground focus:border-primary"
            />
            <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
              <p className={`text-xs ${error ? "text-destructive" : "text-muted-foreground"}`}>
                {error || "The assistant uses read-only warehouse tool calls and does not provide investment advice."}
              </p>
              <button
                type="submit"
                disabled={isSending || !prompt.trim() || !selectedAssetId || !selectedDataSourceId}
                className="inline-flex items-center justify-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-opacity hover:opacity-90 disabled:opacity-60"
              >
                {isSending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
                Send Prompt
              </button>
            </div>
          </form>
        </div>

        <div className="rounded-xl border border-border bg-card p-5">
          <div className="mb-4 flex items-center gap-2">
            <Wrench className="h-4 w-4 text-primary" />
            <h2 className="text-sm font-semibold text-card-foreground">Tool Calls</h2>
          </div>

          {lastResponse?.toolCalls.length ? (
            <div className="space-y-2">
              {lastResponse.toolCalls.map((call, index) => (
                <div key={`${call.name}-${index}`} className="rounded-lg border border-border bg-muted/30 px-3 py-2">
                  <div className="flex items-center justify-between gap-2">
                    <p className="truncate font-mono text-xs font-semibold text-primary">{call.name}</p>
                    <span className="rounded bg-card px-2 py-0.5 text-[10px] font-bold text-muted-foreground">
                      {call.status}
                    </span>
                  </div>
                  <p className="mt-1 text-xs text-muted-foreground">{call.summary}</p>
                </div>
              ))}
            </div>
          ) : (
            <EmptyState title="No tool calls yet" description="Send a prompt to run the agentic workflow." />
          )}
        </div>
      </div>

      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <div className="rounded-xl border border-border bg-card p-5">
          <div className="mb-4 flex items-center gap-2">
            <Database className="h-4 w-4 text-primary" />
            <h2 className="text-sm font-semibold text-card-foreground">Warehouse Instruments</h2>
          </div>
          {instruments.length ? (
            <div className="space-y-2">
              {instruments.slice(0, 6).map((instrument) => (
                <div key={instrument.id} className="flex items-center justify-between gap-3 rounded-lg bg-muted/30 px-3 py-2">
                  <div className="min-w-0">
                    <p className="truncate text-sm font-medium text-card-foreground">{instrument.symbol}</p>
                    <p className="truncate text-xs text-muted-foreground">{instrument.id}</p>
                  </div>
                  <span className="rounded-md bg-secondary px-2 py-0.5 text-xs font-medium text-secondary-foreground">
                    {instrument.class}
                  </span>
                </div>
              ))}
            </div>
          ) : (
            <EmptyState title="No instruments available to agents" />
          )}
        </div>

        <div className="rounded-xl border border-border bg-card p-5">
          <div className="mb-4 flex items-center gap-2">
            <Globe className="h-4 w-4 text-primary" />
            <h2 className="text-sm font-semibold text-card-foreground">Agent Contract</h2>
          </div>
          <div className="space-y-3 text-sm text-muted-foreground">
            <div className="flex gap-2 rounded-lg bg-muted/30 px-3 py-2">
              <Sparkles className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
              <p>Workflow: discover assets, inspect metadata, fetch bounded time-series records, summarize returned data.</p>
            </div>
            <div className="rounded-lg bg-muted/30 px-3 py-2">
              <p className="font-mono text-xs text-accent">POST /api/v1/assistant/chat</p>
              <p className="mt-1 text-xs">LLM provider is configured with LLM_API_KEY. Without a key, deterministic fallback is used.</p>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function inferSourceFromAsset(assetId: string, sources: DataSource[]) {
  if (!assetId) {
    return sources[0]?.id ?? "";
  }

  const inferred = assetId.split("/").slice(0, -1).join("/");
  return sources.some((source) => source.id === inferred)
    ? inferred
    : sources.find((source) => assetId.startsWith(`${source.id}/`))?.id ?? sources[0]?.id ?? "";
}

function defaultEndDate() {
  return new Date().toISOString().slice(0, 10);
}

function defaultStartDate() {
  const date = new Date();
  date.setDate(date.getDate() - 30);
  return date.toISOString().slice(0, 10);
}
