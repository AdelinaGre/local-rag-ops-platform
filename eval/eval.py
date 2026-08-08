import json
import os
import re
import sys
from pathlib import Path
from collections import defaultdict

import chromadb

CHROMA_HOST = "10.0.2.2"
CHROMA_PORT = 8000
COLLECTION_NAME = "rag_collection"

TOP_K_RETRIEVE = 12
TOP_K_GATE = 5

HIT1_GATE = 0.50
HIT5_GATE = 0.80

DATASET_CANDIDATES = [
    Path("eval/dataset.json"),
    Path("eval/dataset.jsonl"),
    Path("eval/eval_dataset.json"),
]


def normalize_path(p: str) -> str:
    if not p:
        return ""
    return p.replace("\\", "/").strip().lower()


def basename(p: str) -> str:
    return os.path.basename(p) if p else ""


def load_dataset():
    dataset_path = next((p for p in DATASET_CANDIDATES if p.exists()), None)
    if dataset_path is None:
        print("Eroare: Nu am putut găsi dataset-ul. Căutate:")
        for p in DATASET_CANDIDATES:
            print(" -", p)
        sys.exit(1)

    print(f"Folosesc dataset: {dataset_path}")

    if dataset_path.suffix.lower() == ".jsonl":
        rows = []
        with open(dataset_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    rows.append(json.loads(line))
        return rows

    with open(dataset_path, "r", encoding="utf-8") as f:
        data = json.load(f)
        if isinstance(data, list):
            return data
        if isinstance(data, dict) and "items" in data:
            return data["items"]

    print("Format dataset invalid. Aștept listă JSON sau {\"items\": [...]}.")
    sys.exit(1)


def coerce_query(item: dict) -> str:
    # suportă și cheia veche "question"
    q = (item.get("query") or item.get("question") or "").strip()
    return q


def coerce_expected_sources(item: dict):
    exp = item.get("expected_sources")
    if isinstance(exp, list):
        return [normalize_path(x) for x in exp if isinstance(x, str) and x.strip()]

    one = item.get("expected_source")
    if isinstance(one, str) and one.strip():
        return [normalize_path(one)]

    return []


def parse_results(raw):
    docs = raw.get("documents", [[]])[0]
    metas = raw.get("metadatas", [[]])[0]
    dists = raw.get("distances", [[]])[0]

    out = []
    for i in range(len(docs)):
        md = metas[i] if i < len(metas) and metas[i] else {}
        source_path = normalize_path(md.get("source_path", ""))
        source_file = normalize_path(md.get("source_file", ""))
        distance = dists[i] if i < len(dists) else None

        out.append({
            "document": docs[i] or "",
            "source_path": source_path,
            "source_file": source_file,
            "distance": float(distance) if distance is not None else None,
        })
    return out


def dedupe_by_path(candidates):
    seen = set()
    out = []
    for c in candidates:
        key = c["source_path"] or c["source_file"] or ""
        if key in seen:
            continue
        seen.add(key)
        out.append(c)
    return out


def query_intent(query: str):
    q = query.lower()

    java_hint = any(k in q for k in [
        "java", "class", "interface", "repository", "service", "controller",
        "impl", "mongodb", "kafka"
    ])

    docs_hint = any(k in q for k in [
        "readme", "documentation", "docs", "main documentation"
    ])

    deploy_hint = any(k in q for k in [
        "deploy", "deployment", "packaged", "docker", "compose", "gradle", "infrastructure"
    ])

    python_hint = any(k in q for k in [
        ".py", "script", "spark", "ml", "regression", "python"
    ])

    return {
        "java_hint": java_hint,
        "docs_hint": docs_hint,
        "deploy_hint": deploy_hint,
        "python_hint": python_hint
    }


def extract_tokens(query: str):
    # păstrează CamelCase, snake_case, etc.
    toks = re.findall(r"[A-Za-z_][A-Za-z0-9_]*", query)
    stop = {
        "where", "which", "what", "how", "is", "the", "a", "an", "to", "for",
        "of", "and", "or", "in", "on", "by", "with", "that", "this", "are",
        "be", "from", "does", "do", "file", "files", "part", "system", "project"
    }
    out = []
    for t in toks:
        tl = t.lower()
        if len(tl) < 3:
            continue
        if tl in stop:
            continue
        out.append(t)
    return out


def path_ext(path: str) -> str:
    b = basename(path)
    _, ext = os.path.splitext(b)
    return ext.lower()


def lexical_and_intent_rerank(query: str, candidates: list):
    intent = query_intent(query)
    tokens = extract_tokens(query)
    tokens_l = [t.lower() for t in tokens]

    rescored = []

    for c in candidates:
        cpath = c["source_path"]
        cfile = c["source_file"]
        doc_head = (c["document"][:700] if c["document"] else "").lower()
        blob = f"{cpath} {cfile} {doc_head}".lower()

        # vector base score (distance mai mic = mai bun)
        base = 0.0
        if c["distance"] is not None:
            base = 1.0 / (1.0 + c["distance"])

        score = base

        # lexical token overlap
        token_hits = 0
        exact_camel_hits = 0
        for t in tokens:
            tl = t.lower()
            if tl in blob:
                token_hits += 1
                # boost extra pentru tokeni camelCase/class-like
                if re.search(r"[A-Z]", t):
                    exact_camel_hits += 1

        score += 0.06 * token_hits
        score += 0.18 * exact_camel_hits

        ext = path_ext(cpath or cfile)

        # intent-aware weighting
        if intent["java_hint"]:
            if ext == ".java":
                score += 0.25
            if "/src/main/java/" in cpath:
                score += 0.18
            if "/src/test/java/" in cpath:
                score -= 0.10

        if intent["python_hint"]:
            if ext == ".py":
                score += 0.20
            if "spark_analysis_ml" in cpath:
                score += 0.10

        if intent["docs_hint"]:
            if basename(cpath) == "readme.md":
                score += 0.45
            elif ext == ".md":
                score += 0.15

        if intent["deploy_hint"]:
            if basename(cpath) in {"docker-compose.yml", "dockerfile", "build.gradle", "settings.gradle"}:
                score += 0.45
            if ext in {".yml", ".yaml", ".gradle"}:
                score += 0.20

        rescored.append((score, c))

    rescored.sort(key=lambda x: x[0], reverse=True)
    return [c for _, c in rescored]


def match_expected(expected_sources, candidate):
    c_path = candidate["source_path"]
    c_file = candidate["source_file"]
    c_base = basename(c_path) if c_path else basename(c_file)

    for e in expected_sources:
        if not e:
            continue
        e = normalize_path(e)
        e_base = basename(e)

        # full path exact / endswith
        if c_path and (c_path == e or c_path.endswith(e)):
            return True

        # basename fallback
        if e_base and (c_base == e_base or basename(c_file) == e_base):
            return True

    return False


def main():
    dataset = load_dataset()
    if not dataset:
        print("Dataset gol.")
        sys.exit(1)

    print(f"Conectare la ChromaDB {CHROMA_HOST}:{CHROMA_PORT}...")
    client = chromadb.HttpClient(host=CHROMA_HOST, port=CHROMA_PORT)
    collection = client.get_collection(name=COLLECTION_NAME)

    total = 0
    hit1 = 0
    hit3 = 0
    hit5 = 0

    buckets = defaultdict(lambda: {"total": 0, "hit5": 0})
    misses = []

    for item in dataset:
        query = coerce_query(item)
        expected_sources = coerce_expected_sources(item)
        difficulty = str(item.get("difficulty", "UNKNOWN")).upper()

        if not query or not expected_sources:
            continue

        total += 1
        buckets[difficulty]["total"] += 1

        raw = collection.query(
            query_texts=[query],
            n_results=TOP_K_RETRIEVE,
            include=["documents", "metadatas", "distances"]
        )

        candidates = parse_results(raw)
        candidates = dedupe_by_path(candidates)
        candidates = lexical_and_intent_rerank(query, candidates)

        top1 = candidates[:1]
        top3 = candidates[:3]
        top5 = candidates[:TOP_K_GATE]

        ok1 = any(match_expected(expected_sources, c) for c in top1)
        ok3 = any(match_expected(expected_sources, c) for c in top3)
        ok5 = any(match_expected(expected_sources, c) for c in top5)

        if ok1:
            hit1 += 1
        if ok3:
            hit3 += 1
        if ok5:
            hit5 += 1
            buckets[difficulty]["hit5"] += 1
        else:
            got = [c["source_path"] or c["source_file"] or "unknown" for c in top5]
            misses.append({
                "difficulty": difficulty,
                "query": query,
                "expected": expected_sources,
                "got": got
            })

    if total == 0:
        print("Nu există itemi valizi în dataset.")
        sys.exit(1)

    h1 = hit1 / total
    h3 = hit3 / total
    h5 = hit5 / total

    print("\nMetrics:")
    print(f"Hit@1: {h1:.2f} ({hit1}/{total})")
    print(f"Hit@3: {h3:.2f} ({hit3}/{total})")
    print(f"Hit@5: {h5:.2f} ({hit5}/{total})")

    print("\nBreakdown pe dificultate (Hit@5):")
    for d in sorted(buckets.keys()):
        bt = buckets[d]["total"]
        bh = buckets[d]["hit5"]
        br = (bh / bt) if bt else 0.0
        print(f" - {d}: {br:.2f} ({bh}/{bt})")

    if misses:
        print("\nMisses (top 5):")
        for m in misses:
            print(f"ERROR [{m['difficulty']}]: '{m['query']}'")
            print(f"   Așteptam una din sursele: {m['expected']}")
            print(f"   Dar modelul a returnat: {m['got']}")
            print()

    if h5 < HIT5_GATE or h1 < HIT1_GATE:
        print(f"Quality gate failed! Need Hit@5>={HIT5_GATE:.2f} and Hit@1>={HIT1_GATE:.2f}")
        sys.exit(1)

    print("Quality gate passed!")


if __name__ == "__main__":
    main()