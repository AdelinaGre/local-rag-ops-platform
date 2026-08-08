import json
import os
import re
import sys
from pathlib import Path
from collections import defaultdict

import chromadb
from sentence_transformers import SentenceTransformer

# ===== Config =====
CHROMA_HOST = "10.0.2.2"
CHROMA_PORT = 8000
COLLECTION_NAME = "rag_collection"

TOP_K_RETRIEVE = 8
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


def extract_query_keywords(query: str):
    """
    Extrage tokeni utili pt reranking lexical.
    Păstrează CamelCase și termeni alfanumerici.
    """
    tokens = re.findall(r"[A-Za-z_][A-Za-z0-9_]*", query)
    stop = {
        "where", "which", "what", "how", "is", "the", "a", "an", "to", "for", "of",
        "and", "or", "in", "on", "by", "with", "that", "this", "are", "be", "from",
        "does", "do", "file", "class", "interface", "project"
    }
    out = []
    for t in tokens:
        tl = t.lower()
        if len(tl) < 3:
            continue
        if tl in stop:
            continue
        out.append(t)
    return out


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


def coerce_expected_sources(item):
    """
    Acceptă:
    - expected_sources: [...]
    - expected_source: "..."
    """
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
            "document": docs[i],
            "source_path": source_path,
            "source_file": source_file,
            "distance": distance,
        })
    return out


def lexical_rerank(query, candidates):
    """
    Re-ranking simplu:
    score = vector_score_component + lexical overlap boost
    """
    keywords = extract_query_keywords(query)
    kw_lower = [k.lower() for k in keywords]

    rescored = []
    for c in candidates:
        path_blob = f"{c['source_path']} {c['source_file']} {c['document'][:400]}".lower()

        lexical_hits = sum(1 for kw in kw_lower if kw in path_blob)
        # distance mic = mai bun; convertim în score de bază
        base = 0.0
        if c["distance"] is not None:
            base = 1.0 / (1.0 + float(c["distance"]))

        score = base + 0.08 * lexical_hits
        rescored.append((score, c))

    rescored.sort(key=lambda x: x[0], reverse=True)
    return [c for _, c in rescored]


def match_expected(expected_sources, candidate):
    """
    Match robust:
    - exact path
    - endswith path (când datasetul are doar sufix util)
    - basename fallback
    """
    c_path = candidate["source_path"]
    c_file = candidate["source_file"]
    c_base = os.path.basename(c_path) if c_path else c_file

    for e in expected_sources:
        if not e:
            continue
        e_base = os.path.basename(e)

        if c_path and (c_path == e or c_path.endswith(e)):
            return True
        if e_base and (c_base == e_base or c_file == e_base):
            return True

    return False


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


def main():
    dataset = load_dataset()
    if not dataset:
        print("Dataset gol.")
        sys.exit(1)

    print(f"Încărcare embedder pentru evaluare...")
    _ = SentenceTransformer("all-MiniLM-L6-v2")  # păstrăm consistența mediului

    print(f"Conectare la ChromaDB {CHROMA_HOST}:{CHROMA_PORT}...")
    client = chromadb.HttpClient(host=CHROMA_HOST, port=CHROMA_PORT)
    collection = client.get_collection(name=COLLECTION_NAME)

    total = 0
    hit1 = 0
    hit3 = 0
    hit5 = 0

    misses = []

    buckets = defaultdict(lambda: {"total": 0, "hit5": 0})

    for idx, item in enumerate(dataset, start=1):
        query = item.get("query", "").strip()
        difficulty = item.get("difficulty", "UNKNOWN").upper()
        expected_sources = coerce_expected_sources(item)

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
        candidates = lexical_rerank(query, candidates)

        top1 = candidates[:1]
        top3 = candidates[:3]
        top5 = candidates[:TOP_K_GATE]

        is_hit1 = any(match_expected(expected_sources, c) for c in top1)
        is_hit3 = any(match_expected(expected_sources, c) for c in top3)
        is_hit5 = any(match_expected(expected_sources, c) for c in top5)

        if is_hit1:
            hit1 += 1
        if is_hit3:
            hit3 += 1
        if is_hit5:
            hit5 += 1
            buckets[difficulty]["hit5"] += 1
        else:
            got = []
            for c in top5:
                got.append(c["source_path"] or c["source_file"] or "unknown")
            misses.append({
                "difficulty": difficulty,
                "query": query,
                "expected": expected_sources,
                "got": got
            })

    if total == 0:
        print("Nu există itemi valizi în dataset.")
        sys.exit(1)

    hit1_rate = hit1 / total
    hit3_rate = hit3 / total
    hit5_rate = hit5 / total

    print(f"\nMetrics:")
    print(f"Hit@1: {hit1_rate:.2f} ({hit1}/{total})")
    print(f"Hit@3: {hit3_rate:.2f} ({hit3}/{total})")
    print(f"Hit@5: {hit5_rate:.2f} ({hit5}/{total})")

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

    # Quality gate strict, dar robust
    if hit5_rate < HIT5_GATE or hit1_rate < HIT1_GATE:
        print(
            f"Quality gate failed! Need Hit@5>={HIT5_GATE:.2f} "
            f"and Hit@1>={HIT1_GATE:.2f}"
        )
        sys.exit(1)

    print("Quality gate passed!")


if __name__ == "__main__":
    main()