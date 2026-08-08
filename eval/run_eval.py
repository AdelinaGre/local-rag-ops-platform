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
    s = (s or "").replace("\\", "/").strip().lower()
    s = re.sub(r"/+", "/", s)
    return s


def base(p: str) -> str:
    return os.path.basename(p) if p else ""


def canonicalize_expected(exp: str) -> str:
    e = norm(exp)
    # uniformize common docs path variants
    if e == "readme.md":
        return "readme.md"
    return e


def collect_retrieved_paths(metadatas):
    out = []
    for md in metadatas or []:
        if not md:
            continue
        p = norm(md.get("source_path", ""))
        f = norm(md.get("source_file", ""))
        # prefer source_path, fallback source_file
        cand = p or f
        if cand:
            out.append(cand)

    # dedupe preserving order
    seen = set()
    deduped = []
    for x in out:
        if x not in seen:
            deduped.append(x)
            seen.add(x)
    return deduped


def source_match(expected: str, got: str) -> bool:
    e = canonicalize_expected(expected)
    g = norm(got)

    if not e or not g:
        return False

    e_base = base(e)
    g_base = base(g)

    # 1) exact
    if g == e:
        return True

    # 2) endswith full expected path
    if g.endswith(e):
        return True

    # 3) basename exact
    if e_base and g_base and e_base == g_base:
        return True

    # 4) special case for README ambiguity:
    # accept only if expected is nested README and got endswith that nested path
    # otherwise basename-only "readme.md" is too weak.
    if e_base == "readme.md":
        # if expected is full path, require suffix path or exact
        if "/" in e and g.endswith(e):
            return True
        return False

    return False


def has_expected_source(expected_sources, retrieved_sources):
    for exp in expected_sources:
        for got in retrieved_sources:
            if source_match(exp, got):
                return True
    return False


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
        q = (item.get("question") or "").strip()
        expected = [norm(x) for x in item.get("expected_sources", []) if isinstance(x, str) and x.strip()]
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

        retrieved_sources = collect_retrieved_paths(metas)
        hit = has_expected_source(expected, retrieved_sources)

        if hit:
            source_hits += 1

        kr = keyword_recall(required_keywords, joined_docs)
        keyword_recalls.append(kr)

        if (not hit) or (kr < 1.0):
            failures.append({
                "question": q,
                "expected_sources": expected,
                "retrieved_sources": retrieved_sources,
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