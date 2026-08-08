import json
import os
import re
import sys
from pathlib import Path

import chromadb

CHROMA_HOST = "10.0.2.2"
CHROMA_PORT = 8000
COLLECTION_NAME = "rag_collection"

TOP_K = 5
GOLDEN_PATH = Path("eval/golden_set.json")
RESULTS_PATH = Path("eval/results.json")


def norm(s: str) -> str:
    return (s or "").replace("\\", "/").strip().lower()


def has_expected_source(expected_sources, metadatas):
    got_paths = []
    for md in metadatas:
        p = norm(md.get("source_path", "")) if md else ""
        f = norm(md.get("source_file", "")) if md else ""
        got_paths.append(p or f)

    for exp in expected_sources:
        e = norm(exp)
        e_base = os.path.basename(e)
        for g in got_paths:
            if not g:
                continue
            if g == e or g.endswith(e) or os.path.basename(g) == e_base:
                return True, got_paths
    return False, got_paths


def keyword_recall(required_keywords, documents_text):
    if not required_keywords:
        return 1.0
    text = (documents_text or "").lower()
    hits = 0
    for kw in required_keywords:
        if kw.lower() in text:
            hits += 1
    return hits / len(required_keywords)


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
    keyword_recalls = []
    failures = []

    for item in golden:
        q = item.get("question", "").strip()
        expected = item.get("expected_sources", [])
        required_keywords = item.get("required_keywords", [])

        if not q or not expected:
            continue

        total += 1
        result = collection.query(
            query_texts=[q],
            n_results=TOP_K,
            include=["documents", "metadatas"]
        )

        docs = result.get("documents", [[]])[0]
        metas = result.get("metadatas", [[]])[0]

        joined_docs = "\n".join([d for d in docs if d]) if docs else ""
        if not joined_docs.strip():
            empty_context += 1

        hit, got_paths = has_expected_source(expected, metas)
        if hit:
            source_hits += 1

        kr = keyword_recall(required_keywords, joined_docs)
        keyword_recalls.append(kr)

        if (not hit) or (kr < 1.0):
            failures.append({
                "question": q,
                "expected_sources": expected,
                "retrieved_sources": got_paths,
                "keyword_recall": kr
            })

    if total == 0:
        print("No valid items in golden set.")
        sys.exit(1)

    source_hit_rate = source_hits / total
    answer_keyword_recall = sum(keyword_recalls) / len(keyword_recalls) if keyword_recalls else 0.0
    empty_context_rate = empty_context / total

    results = {
        "total": total,
        "source_hit_rate": round(source_hit_rate, 4),
        "answer_keyword_recall": round(answer_keyword_recall, 4),
        "empty_context_rate": round(empty_context_rate, 4),
        "top_k": TOP_K,
        "failures": failures
    }

    RESULTS_PATH.parent.mkdir(parents=True, exist_ok=True)
    with open(RESULTS_PATH, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)

    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()