import os
import requests

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import List, Optional

import chromadb
from sentence_transformers import SentenceTransformer

app = FastAPI(title="RAG-Ops Orchestrator")

# ============================================================
# CONFIGURATION
# ============================================================

CHROMA_HOST = os.getenv("CHROMA_HOST", "localhost")
CHROMA_PORT = int(os.getenv("CHROMA_PORT", "8000"))

LLAMA_API_URL = os.getenv(
    "LLAMA_BASE_URL",
    "http://llama-server:8080/v1"
)

# Retrieve more candidates first, then filter them.
RETRIEVAL_K = int(os.getenv("RETRIEVAL_K", "15"))

# Maximum chunks sent to LLM context
TOP_K = int(os.getenv("TOP_K", "10"))

# Relative distance margin from the best non-RAG result.
RELATIVE_DISTANCE_MARGIN = float(
    os.getenv("RELATIVE_DISTANCE_MARGIN", "0.20")
)

# How much distance penalty to apply per priority level (0 to 4).
# 0.05 means a Test file (Priority 4) gets a +0.20 penalty to its distance.
PRIORITY_PENALTY_WEIGHT = float(
    os.getenv("PRIORITY_PENALTY_WEIGHT", "0.05")
)

# Maximum number of unique source files sent to the LLM.
MAX_SOURCES = int(
    os.getenv("MAX_SOURCES", "5")
)

# Whether to append a human-readable "Sources" footer to the answer text.
# This is what actually makes sources visible in Open WebUI: when this
# service is registered as a plain OpenAI-Compatible connection, Open WebUI
# only renders the `content` field of the message — any extra top-level
# JSON fields (like `sources` or `rag`) are silently dropped. Embedding the
# source list directly in the text is the only reliable way to show it.
APPEND_SOURCES_FOOTER = os.getenv("APPEND_SOURCES_FOOTER", "true").lower() == "true"

# Fallback token budget for real (non-system-task) answers when the client
# doesn't specify max_tokens. Explanatory, Claude-style answers need more
# room than a one-line pointer to a file — without this, llama.cpp's own
# default may cut the explanation short.
DEFAULT_ANSWER_MAX_TOKENS = int(os.getenv("DEFAULT_ANSWER_MAX_TOKENS", "1200"))

# Files that must never be retrieved as project context.
EXCLUDED_FILES = {
    "main.py",
}

# Path fragments that indicate RAG/infrastructure/evaluation code.
EXCLUDED_PATH_PARTS = {
    "/rag/",
    "\\rag\\",
    "/eval/",
    "\\eval\\",
}

# Markers that indicate an internal UI task (tags, titles, etc.)
SYSTEM_TASK_MARKERS = [
    "### task:",
    "generate title",
    "generate follow-up",
    "generate 1-3 broad tags",
    "<chat_history>"
]

# ============================================================
# INITIALIZATION
# ============================================================

print("Se încarcă modelul de embedding...")

embedder = SentenceTransformer(
    "all-MiniLM-L6-v2"
)

print(
    f"Se conectează la ChromaDB pe "
    f"{CHROMA_HOST}:{CHROMA_PORT}..."
)

chroma_client = chromadb.HttpClient(
    host=CHROMA_HOST,
    port=CHROMA_PORT
)

collection = chroma_client.get_or_create_collection(
    name="rag_collection"
)

print("RAG API initialization completed.")


# ============================================================
# PYDANTIC MODELS
# ============================================================

class ChatMessage(BaseModel):
    role: str
    content: str


class ChatRequest(BaseModel):
    model: str
    messages: List[ChatMessage]
    temperature: Optional[float] = 0.7
    stream: Optional[bool] = False
    max_tokens: Optional[int] = None


# ============================================================
# SOURCE FILTERING
# ============================================================

def is_excluded_source(
        source_file: str,
        source_path: str
) -> bool:
    file_lower = (source_file or "").lower()
    path_lower = (source_path or "").lower()

    if file_lower in EXCLUDED_FILES:
        return True

    for part in EXCLUDED_PATH_PARTS:
        if part.lower() in path_lower:
            return True

    rag_files = {
        "main.py",
        "rag.py",
        "rag_api.py",
        "rag_server.py",
        "orchestrator.py",
    }

    if file_lower in rag_files:
        return True

    excluded_extensions = {
        ".pyc",
        ".pyo",
    }

    if any(file_lower.endswith(ext) for ext in excluded_extensions):
        return True

    return False


# ============================================================
# SOURCE PRIORITY
# ============================================================

def source_priority(
        source_file: str,
        user_query: str
) -> int:
    name = (source_file or "").lower()
    query = (user_query or "").lower()

    if (
            name.endswith("test.java")
            or name.endswith("tests.java")
            or "test" in name
    ):
        if any(
                word in query
                for word in ["test", "testing", "unit test", "teste", "testing"]
        ):
            return 0
        return 4

    if name.endswith("impl.java"):
        return 0

    if "repository" in name:
        return 1

    if "service" in name:
        return 1

    if "controller" in name or "resource" in name or "endpoint" in name:
        return 2

    if "config" in name or "configuration" in name or "dto" in name or "model" in name:
        return 3

    return 3


# ============================================================
# SOURCES FOOTER FORMATTING
# ============================================================

def format_sources_footer(citation_list: list) -> str:
    """
    Builds a Markdown footer listing the numbered sources so they are
    visible directly in the chat message text. This is what Open WebUI
    actually renders (see APPEND_SOURCES_FOOTER note above).
    """
    if not citation_list:
        return ""

    lines = ["", "---", "**Surse:**"]
    for c in citation_list:
        file_name = c.get("file", "unknown")
        path = c.get("path", "")
        if path:
            lines.append(f"[{c['id']}] `{path}`")
        else:
            lines.append(f"[{c['id']}] `{file_name}`")

    return "\n".join(lines)


# ============================================================
# HEALTH CHECK
# ============================================================

@app.get("/health")
def health():
    return {
        "status": "ok",
        "role": "RAG Orchestrator"
    }


# ============================================================
# MODELS ENDPOINT
# ============================================================

@app.get("/v1/models")
def models():
    return {
        "object": "list",
        "data": [
            {
                "id": "rag-model",
                "object": "model",
                "owned_by": "rag-api"
            }
        ]
    }


# ============================================================
# OPENAI-COMPATIBLE CHAT ENDPOINT
# ============================================================

@app.post("/v1/chat/completions")
def chat_completions(req: ChatRequest):
    # --------------------------------------------------------
    # 1. Get latest user message
    # --------------------------------------------------------

    user_messages = [msg for msg in req.messages if msg.role == "user"]

    if not user_messages:
        raise HTTPException(
            status_code=400,
            detail="No user message found"
        )

    user_query = user_messages[-1].content.strip()

    print("\n========== RAG REQUEST ==========")
    print("USER QUERY:", repr(user_query))
    print("=================================\n")

    # --------------------------------------------------------
    # 1.5 Bypass RAG for system / UI tasks
    # --------------------------------------------------------

    is_system_task = False
    lower_query = user_query.lower()
    if any(marker in lower_query for marker in SYSTEM_TASK_MARKERS):
        is_system_task = True
        print("[INFO] System/UI task detected (Tags/Title/Follow-ups). Bypassing ChromaDB retrieval.")

    # Initialize variables for the final LLM payload
    messages = [message.model_dump() for message in req.messages]
    citations = []

    documents_retrieved = 0
    filtered_candidates_count = 0
    selected_candidates_count = 0
    unique_sources_count = 0

    # ========================================================
    # RAG PIPELINE (Execute only for real user queries)
    # ========================================================
    if not is_system_task:
        # --------------------------------------------------------
        # 2. Create embedding
        # --------------------------------------------------------
        try:
            query_embedding = embedder.encode(user_query).tolist()
        except Exception as e:
            print("Embedding error:", e)
            raise HTTPException(status_code=500, detail=f"Embedding error: {str(e)}")

        # --------------------------------------------------------
        # 3. Search ChromaDB
        # --------------------------------------------------------
        try:
            results = collection.query(
                query_embeddings=[query_embedding],
                n_results=RETRIEVAL_K,
                include=["documents", "metadatas", "distances"]
            )
        except Exception as e:
            print("ChromaDB error:", e)
            raise HTTPException(status_code=500, detail=f"ChromaDB error: {str(e)}")

        documents = results.get("documents", [[]])[0] if results.get("documents") else []
        metadatas = results.get("metadatas", [[]])[0] if results.get("metadatas") else []
        distances = results.get("distances", [[]])[0] if results.get("distances") else []
        documents_retrieved = len(documents)

        # --------------------------------------------------------
        # 4. Filter + rank + deduplicate
        # --------------------------------------------------------
        candidates = []

        for i, doc in enumerate(documents):
            metadata = metadatas[i] if i < len(metadatas) else {}
            distance = distances[i] if i < len(distances) else float("inf")

            source_file = metadata.get("source_file", "unknown")
            source_path = metadata.get("source_path", "")
            chunk_id = metadata.get("chunk_id", f"chunk_{i}")

            # 4.1 Exclude RAG infrastructure
            if is_excluded_source(source_file, source_path):
                print(f"[FILTERED - RAG] {source_file} distance={distance:.4f}")
                continue

            candidates.append({
                "document": doc,
                "metadata": metadata,
                "source_file": source_file,
                "source_path": source_path,
                "chunk_id": chunk_id,
                "distance": distance,
            })

        # 4.3 Relative relevance filtering
        if candidates:
            best_distance = min(candidate["distance"] for candidate in candidates)
            relevance_threshold = best_distance + RELATIVE_DISTANCE_MARGIN
            filtered_candidates = []

            for candidate in candidates:
                if candidate["distance"] <= relevance_threshold:
                    filtered_candidates.append(candidate)
                else:
                    print(
                        f"[FILTERED - DISTANCE] {candidate['source_file']} "
                        f"distance={candidate['distance']:.4f} threshold={relevance_threshold:.4f}"
                    )
            candidates = filtered_candidates

        filtered_candidates_count = len(candidates)

        # 4.4 Rank candidates (Adjusted Distance)
        for candidate in candidates:
            priority = source_priority(candidate["source_file"], user_query)
            candidate["priority"] = priority

            # Apply mathematical penalty to allow distance to dominate, but punish test/irrelevant files softly
            candidate["adjusted_distance"] = candidate["distance"] + (priority * PRIORITY_PENALTY_WEIGHT)

        # Sort primarily by adjusted_distance (lowest is best)
        candidates.sort(key=lambda x: (x["adjusted_distance"], x["distance"]))

        # 4.5 Deduplicate chunks
        selected_candidates = []
        seen_chunks = set()

        for candidate in candidates:
            chunk_key = (candidate["source_file"], candidate["chunk_id"])
            if chunk_key in seen_chunks:
                continue

            seen_chunks.add(chunk_key)
            selected_candidates.append(candidate)

            if len(selected_candidates) >= TOP_K:
                break

        selected_candidates_count = len(selected_candidates)

        # 4.6 Unique citation metadata
        unique_citation_metadata = []
        seen_sources = set()

        for candidate in selected_candidates:
            source_file = candidate["source_file"]
            if source_file in seen_sources:
                continue

            seen_sources.add(source_file)
            unique_citation_metadata.append(candidate)

            if len(unique_citation_metadata) >= MAX_SOURCES:
                break

        unique_sources_count = len(unique_citation_metadata)

        # 4.7 Logging
        print("\n========== FILTERED & RANKED SOURCES ==========")
        for candidate in candidates:
            print(
                f"{candidate['source_file']} "
                f"raw_dist={candidate['distance']:.4f} "
                f"adj_dist={candidate['adjusted_distance']:.4f} "
                f"prio={candidate['priority']}"
            )

        # 5. Build context
        context_parts = []
        for index, candidate in enumerate(selected_candidates, start=1):
            source_file = candidate["source_file"]
            source_path = candidate["source_path"]
            document = candidate["document"]
            distance = candidate["distance"]

            context_parts.append(
                f'<source id="{index}">\n'
                f"File: {source_file}\n"
                f"Path: {source_path}\n"
                f"Relevance distance: {distance:.4f}\n"
                f"Content:\n{document}\n"
                f"</source>"
            )

        for index, candidate in enumerate(unique_citation_metadata, start=1):
            citations.append({
                "id": index,
                "file": candidate["source_file"],
                "path": candidate["source_path"],
                "distance": round(candidate["distance"], 4),
            })

        context_text = "\n\n---\n\n".join(context_parts)

        # 6. Build RAG prompt
        if context_text:
            augmented_prompt = f"""
You are a Senior Data Engineer explaining a software repository to a
colleague who wants to actually understand how it works, not just get
a file name pointed at them.

Answer the user's question using ONLY the retrieved repository
context below.

RESPONSE SHAPE (follow this order):

1. Start with a short, direct answer to the question (1-2 sentences).
2. Then explain HOW it works: walk through the relevant logic in your
   own words — name the key classes/methods/fields involved and what
   each one actually does. Do not just paste code back; explain the
   reasoning and flow (e.g. "the query first does X, then Y, because Z").
3. If more than one file is involved, briefly explain how they relate
   to each other (e.g. interface vs implementation, caller vs callee).
4. Use short paragraphs or bullet points for anything non-trivial —
   avoid a single dense wall of text.

IMPORTANT RULES:

1. Do not invent files, classes, methods, queries, or behavior that
   is not present in the retrieved context.
2. Do not mention the RAG system itself.
3. Do not mention main.py unless the user explicitly asks about it.
4. Do not mention irrelevant files.
5. Prefer actual implementation files over test files when
   answering implementation questions.
6. Cite sources inline, next to the specific claim they support, in
   the format [1], [2], etc. Each source should be cited at most once.
7. Do not create a separate "Sources used" section — the system adds
   one automatically after your answer.
8. Answer in the same language as the user's question.
9. If the retrieved context is insufficient to explain the answer,
   say so explicitly rather than filling gaps with assumptions:
   "I could not find enough relevant context to answer this question."

RETRIEVED CONTEXT:

{context_text}

USER QUESTION:

{user_query}
"""
        else:
            augmented_prompt = f"""
You are a strict technical assistant.

The retrieval system did not find sufficiently relevant
repository documents.

Do not invent an answer.

If the information cannot be determined from the retrieved
documents, clearly say:

"I could not find enough relevant context to answer this question."

Answer in the same language as the user.

USER QUESTION:

{user_query}
"""

        # 7. Replace only the latest user message with context
        messages[-1]["content"] = augmented_prompt

    # ========================================================
    # 8. Prepare llama.cpp request (For both RAG and Bypassed tasks)
    # ========================================================
    payload = {
        "model": req.model,
        "messages": messages,
        "temperature": req.temperature,
        "stream": False
    }

    if req.max_tokens is not None:
        payload["max_tokens"] = req.max_tokens
    elif not is_system_task:
        payload["max_tokens"] = DEFAULT_ANSWER_MAX_TOKENS

    print("\n========== LLAMA REQUEST ==========")
    print("LLAMA URL:", LLAMA_API_URL)
    print("MODEL:", req.model)
    print("IS SYSTEM TASK:", is_system_task)
    print("SELECTED CHUNKS FOR CONTEXT:", selected_candidates_count)
    print("UNIQUE CITATIONS:", len(citations))
    print("===================================\n")

    # --------------------------------------------------------
    # 9. Send request to llama.cpp
    # --------------------------------------------------------
    try:
        resp = requests.post(
            f"{LLAMA_API_URL}/chat/completions",
            json=payload,
            timeout=300
        )
        resp.raise_for_status()
        llama_data = resp.json()

    except requests.exceptions.Timeout:
        raise HTTPException(status_code=504, detail="Timeout communicating with llama.cpp")
    except requests.exceptions.RequestException as e:
        print("ERROR communicating with llama.cpp:", e)
        raise HTTPException(status_code=500, detail=f"Error communicating with llama.cpp: {str(e)}")
    except ValueError as e:
        raise HTTPException(status_code=500, detail=f"Invalid JSON response from llama.cpp: {str(e)}")

    # --------------------------------------------------------
    # 10. Clean LLM answer
    # --------------------------------------------------------
    try:
        answer = llama_data["choices"][0]["message"]["content"].strip()
    except (KeyError, IndexError, TypeError):
        raise HTTPException(status_code=500, detail="Unexpected response format from llama.cpp")

    # --------------------------------------------------------
    # 10.5 Append a human-readable sources footer directly into the
    # answer text. This is the part that actually shows up in Open
    # WebUI: when this service is registered as a plain OpenAI-Compatible
    # connection, Open WebUI only ever renders `message.content` — it
    # does not read custom top-level fields like `sources` or `rag`.
    # --------------------------------------------------------
    if APPEND_SOURCES_FOOTER and not is_system_task and citations:
        answer = answer + format_sources_footer(citations)

    # --------------------------------------------------------
    # 11. Return clean citations separately
    # --------------------------------------------------------
    llama_data["choices"][0]["message"]["content"] = answer

    # Add structured RAG metadata. Kept for debugging / for clients that
    # do read the raw API response directly (e.g. curl, your own scripts).
    # Open WebUI itself will ignore these two fields — see note above.
    llama_data["rag"] = {
        "bypassed": is_system_task,
        "retrieved": documents_retrieved,
        "filtered": filtered_candidates_count,
        "selected": selected_candidates_count,
        "unique_sources": unique_sources_count,
        "relative_distance_margin": RELATIVE_DISTANCE_MARGIN
    }

    llama_data["sources"] = citations

    # --------------------------------------------------------
    # 12. Return OpenAI-compatible response
    # --------------------------------------------------------
    return llama_data