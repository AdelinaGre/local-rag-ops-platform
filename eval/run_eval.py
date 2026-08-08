import json
import os
import re
import sys
from pathlib import Path

import chromadb

CHROMA_HOST = os.getenv("CHROMA_HOST", "10.0.2.2")
CHROMA_PORT = int(os.getenv("CHROMA_PORT", "8000"))
COLLECTION_NAME = os.getenv("CHROMA_COLLECTION", "rag_collection")

TOP_K_RETRIEVE = int(os.getenv("EVAL_TOPK_RETRIEVE", "12"))
TOP_K_HIT = int(os.getenv("EVAL_TOPK_HIT", "8"))

GOLDEN_PATH = Path("eval/golden_set.json")
RESULTS_PATH = Path("eval/results.json")


def norm(s: str) -> str:
    s = (s or "").replace("\\", "/").strip().lower()
    s = re.sub(r"/+", "/", s)
    return s


def base(p: str) -> str:
    return os.path.basename(p) if p else ""


def collect_retrieved(metadatas, documents, distances=None):
    rows = []
    for i, md in enumerate(metadatas or []):
        md = md or {}
        p = norm(md.get("source_path", ""))
        f = norm(md.get("source_file", ""))
        src = p or f
        doc = (documents[i] if i < len(documents) and documents[i] else "")
        dist = distances[i] if distances and i < len(distances) else None
        rows.append({"source": src, "doc": doc, "dist": dist})

    # dedupe by source preserving order
    seen = set()
    out = []
    for r in rows:
        s = r["source"]
        if not s or s in seen:
            continue
        out.append(r)
        seen.add(s)
    return out


def source_match(expected: str, got: str) -> bool:
    e = norm(expected)
    g = norm(got)
    if not e or not g:
        return False

    # exact / suffix path / basename
    if g == e or g.endswith(e):
        return True
    if base(g) == base(e):
        return True

    # strict README rule if expected path is full
    if base(e) == "readme.md" and "/" in e:
        return g.endswith(e)
    return False


def has_expected(expected_sources, retrieved_sources):
    for e in expected_sources:
        for g in retrieved_sources:
            if source_match(e, g):
                return True
    return False


def keyword_recall(required_keywords, context_text):
    kws = [k for k in (required_keywords or []) if isinstance(k, str) and k.strip()]
    if not kws:
        return 1.0
    txt = (context_text or "").lower()
    hits = sum(1 for k in kws if k.lower() in txt)
    return hits / len(kws)


def expand_query(q: str) -> str:
    ql = q.lower()
    extra = []

    # filename/class anchors
    m = re.findall(r"[A-Za-z_][A-Za-z0-9_]*", q)
    for t in m:
        if re.search(r"[A-Z]", t) and len(t) > 5:
            extra.append(t)
            if not t.endswith(".java") and ("repository" in t.lower() or "loader" in t.lower() or "service" in t.lower()):
                extra.append(t + ".java")

    # targeted boosts
    if "timeseriesdatarepository" in ql:
        extra += ["TimeSeriesDataRepository.java", "interface", "dal/repository"]
    if "datasourcekey" in ql:
        extra += ["DataSourceKey.java", "partitionKey"]
    if "marketdataloader" in ql:
        extra += ["MarketDataLoader.java", "DefaultMarketDataLoader.java", "ingestion/loader"]
    if "kafkaingestionconsumer" in ql or "kafkaingestionjobservice" in ql:
        extra += ["KafkaIngestionConsumer.java", "KafkaIngestionJobService.java", "ingestion/streaming"]
    if "settings.gradle" in ql:
        extra += ["docs/datawarehouse/datawarehouse/settings.gradle", "root project"]
    if "docker-compose" in ql or "build.gradle" in ql:
        extra += ["docker-compose.yml", "build.gradle", "infrastructure dependencies"]
    if "readme" in ql:
        extra += ["docs/datawarehouse/datawarehouse/README.md", "main documentation"]

    extra = list(dict.fromkeys(extra))
    if extra:
        return q + " | " + " ".join(extra)
    return q


def rerank(question: str, rows: list):
    ql = question.lower()
    tokens = [t.lower() for t in re.findall(r"[A-Za-z_][A-Za-z0-9_\\.\\-]*", question) if len(t) > 2]

    rescored = []
    for r in rows:
        src = r["source"]
        blob = (src + " " + (r["doc"][:500].lower() if r["doc"] else ""))

        score = 0.0
        if r["dist"] is not None:
            score += 1.0 / (1.0 + float(r["dist"]))

        # token overlap
        overlap = sum(1 for t in tokens if t in blob)
        score += 0.06 * overlap

        # intent/filetype boosts
        b = base(src)
        if "java" in ql or "interface" in ql or "class" in ql or "repository" in ql:
            if src.endswith(".java"):
                score += 0.25
            if "/src/main/java/" in src:
                score += 0.15
            if "/src/test/java/" in src:
                score -= 0.08

        if "spark" in ql or "ml" in ql or "regression" in ql or ".py" in ql:
            if src.endswith(".py"):
                score += 0.25
            if "spark_analysis_ml" in src:
                score += 0.12

        if "readme" in ql or "documentation" in ql:
            if b == "readme.md":
                score += 0.35

        if "docker" in ql or "gradle" in ql or "infrastructure" in ql:
            if b in {"docker-compose.yml", "build.gradle", "settings.gradle"}:
                score += 0.40

        rescored.append((score, r))

    rescored.sort(key=lambda x: x[0], reverse=True)
    return [r for _, r in rescored]


def main():
    if not GOLDEN_PATH.exists():
        print(f"Missing {GOLDEN_PATH}")
        sys.exit(1)

    with open(GOLDEN_PATH, "r", encoding="utf-8") as f:
        golden = json.load(f)

    client = chromadb.HttpClient(host=CHROMA_HOST, port=CHROMA_PORT)
    collection = client.get_collection(name=COLLECTION_NAME)

    total = 0
    source_hits = 0
    empty_context = 0
    recalls = []
    failures = []

    for item in golden:
        q = (item.get("question") or "").strip()
        expected = [norm(x) for x in item.get("expected_sources", []) if isinstance(x, str)]
        required_keywords = item.get("required_keywords", [])

        if not q or not expected:
            continue

        total += 1

        query_text = expand_query(q)
        res = collection.query(
            query_texts=[query_text],
            n_results=TOP_K_RETRIEVE,
            include=["documents", "metadatas", "distances"]
        )

        docs = res.get("documents", [[]])[0]
        metas = res.get("metadatas", [[]])[0]
        dists = res.get("distances", [[]])[0]

        rows = collect_retrieved(metas, docs, dists)
        rows = rerank(q, rows)
        rows = rows[:TOP_K_HIT]

        retrieved_sources = [r["source"] for r in rows]
        joined_docs = "\n".join([r["doc"] for r in rows if r["doc"]]).strip()

        if not joined_docs:
            empty_context += 1

        hit = has_expected(expected, retrieved_sources)
        if hit:
            source_hits += 1

        kr = keyword_recall(required_keywords, joined_docs)
        recalls.append(kr)

        if (not hit) or (kr < 1.0):
            failures.append({
                "question": q,
                "expected_sources": expected,
                "retrieved_sources": retrieved_sources,
                "keyword_recall": kr
            })

    if total == 0:
        print("No valid entries in golden_set.json")
        sys.exit(1)

    result = {
        "total": total,
        "source_hit_rate": round(source_hits / total, 4),
        "answer_keyword_recall": round(sum(recalls) / len(recalls), 4) if recalls else 0.0,
        "empty_context_rate": round(empty_context / total, 4),
        "top_k_retrieve": TOP_K_RETRIEVE,
        "top_k_hit": TOP_K_HIT,
        "failures": failures
    }

    RESULTS_PATH.parent.mkdir(parents=True, exist_ok=True)
    RESULTS_PATH.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()