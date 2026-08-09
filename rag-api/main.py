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

# Maximum Chroma distance accepted.
MAX_DISTANCE = 1.05

# Maximum number of unique source files sent to the LLM.
MAX_SOURCES = int(
    os.getenv("MAX_SOURCES", "5")
)

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

    print("\n========== CHROMA RESULTS ==========")
    print("Retrieved:", len(results.get("documents", [[]])[0]))
    print("Distances:", results.get("distances"))
    print("====================================\n")

    # --------------------------------------------------------
    # 4. Filter + rank + deduplicate
    # --------------------------------------------------------

    candidates = []

    documents = results.get("documents", [[]])[0] if results.get("documents") else []
    metadatas = results.get("metadatas", [[]])[0] if results.get("metadatas") else []
    distances = results.get("distances", [[]])[0] if results.get("distances") else []

    for i, doc in enumerate(documents):

        metadata = metadatas[i] if i < len(metadatas) else {}
        distance = distances[i] if i < len(distances) else 999.0

        source_file = metadata.get("source_file", "unknown")
        source_path = metadata.get("source_path", "")
        chunk_id = metadata.get("chunk_id", f"chunk_{i}")

        # ----------------------------------------------------
        # 4.1 Exclude RAG infrastructure
        # ----------------------------------------------------
        if is_excluded_source(source_file, source_path):
            print(f"[FILTERED - RAG] {source_file}")
            continue

        # ----------------------------------------------------
        # 4.2 Collect non-RAG candidates
        # ----------------------------------------------------

        candidates.append(
            {
                "document": doc,
                "metadata": metadata,
                "source_file": source_file,
                "source_path": source_path,
                "chunk_id": chunk_id,
                "distance": distance
            }
        )

        # ============================================================
        # 4.3 RELATIVE RELEVANCE FILTERING
        # ============================================================

        if candidates:

            # Best semantic result
            best_distance = min(
                candidate["distance"]
                for candidate in candidates
            )

            # Allow results reasonably close to the best result
            RELATIVE_DISTANCE_MARGIN = 0.20

            relevance_threshold = (
                    best_distance +
                    RELATIVE_DISTANCE_MARGIN
            )

            filtered_candidates = []

            for candidate in candidates:

                if candidate["distance"] <= relevance_threshold:

                    filtered_candidates.append(candidate)

                else:

                    print(
                        f"[FILTERED - DISTANCE] "
                        f"{candidate['source_file']} "
                        f"distance={candidate['distance']:.4f}"
                    )

            candidates = filtered_candidates
    # ============================================================
    # 4.4 RANK CANDIDATES
    # ============================================================

    for candidate in candidates:
        candidate["priority"] = source_priority(
            candidate["source_file"],
            user_query
        )

    # Semantic relevance remains the primary signal.
    # Source priority is only a secondary signal.

    candidates.sort(
        key=lambda x: (
            x["distance"],
            x["priority"]
        )
    )
    # ============================================================
    # 4.5 DEDUPLICATE CHUNKS
    # ============================================================

    selected_candidates = []

    seen_chunks = set()

    for candidate in candidates:

        chunk_key = (
            candidate["source_file"],
            candidate["chunk_id"]
        )

        if chunk_key in seen_chunks:
            continue

        seen_chunks.add(chunk_key)

        selected_candidates.append(candidate)

        if len(selected_candidates) >= TOP_K:
            break
    # ============================================================
    # 4.6 UNIQUE SOURCES FOR CITATIONS
    # ============================================================

    unique_sources = []
    seen_sources = set()
    unique_citation_metadata = []

    for candidate in selected_candidates:
        source_file = candidate["source_file"]

        if source_file in seen_sources:
            continue

        seen_sources.add(source_file)
        unique_sources.append(source_file)
        unique_citation_metadata.append(candidate)

        if len(unique_sources) >= MAX_SOURCES:
            break

    # ============================================================
    # 4.7 LOGGING
    # ============================================================

    print("\n========== FILTERED SOURCES ==========")
    for candidate in candidates:
        print(
            f"{candidate['source_file']} "
            f"distance={candidate['distance']:.4f} "
            f"priority={candidate['priority']}"
        )

    print("\n========== SELECTED SOURCES ==========")
    for candidate in selected_candidates:
        print(
            f"{candidate['source_file']} "
            f"distance={candidate['distance']:.4f} "
            f"priority={candidate['priority']}"
        )

    print("\n========== CITATION SOURCES ==========")
    for source in unique_sources:
        print(source)
    print("=======================================\n")

    # --------------------------------------------------------
    # 5. Build context
    # --------------------------------------------------------

    context_parts = []
    citations = []

    # Send selected chunks to LLM Context
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

    # Return Unique Sources for API Citations (Deduplicated)
    for index, candidate in enumerate(unique_citation_metadata, start=1):
        citations.append(
            {
                "id": index,
                "file": candidate["source_file"],
                "path": candidate["source_path"],
                "distance": round(candidate["distance"], 4)
            }
        )

    context_text = "\n\n---\n\n".join(context_parts)

    # --------------------------------------------------------
    # 6. Build RAG prompt
    # --------------------------------------------------------

    if context_text:
        augmented_prompt = f"""
You are a Senior Data Engineer analyzing a software repository.

Answer the user's question using ONLY the retrieved repository
context below.

IMPORTANT RULES:

1. Answer the question directly.
2. Do not invent files, classes, methods, queries, or behavior.
3. Do not mention the RAG system itself.
4. Do not mention main.py unless the user explicitly asks about it.
5. Do not mention irrelevant files.
6. Prefer actual implementation files over test files when
   answering implementation questions.
7. Each source should be cited at most once.
8. Use citations in this format: [1], [2], etc.
9. Do not create a separate "Sources used" section.
10. Answer in the same language as the user's question.
11. If the retrieved context is insufficient, say:
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

    # --------------------------------------------------------
    # 7. Replace only the latest user message
    # --------------------------------------------------------

    messages = [message.model_dump() for message in req.messages]
    messages[-1]["content"] = augmented_prompt

    # --------------------------------------------------------
    # 8. Prepare llama.cpp request
    # --------------------------------------------------------

    payload = {
        "model": req.model,
        "messages": messages,
        "temperature": req.temperature,
        "stream": False
    }

    if req.max_tokens is not None:
        payload["max_tokens"] = req.max_tokens

    print("\n========== LLAMA REQUEST ==========")
    print("LLAMA URL:", LLAMA_API_URL)
    print("MODEL:", req.model)
    print("SELECTED CHUNKS FOR CONTEXT:", len(selected_candidates))
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
        print("ERROR communicating with llama.cpp:")
        print(e)
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
    # 11. Return clean citations separately
    # --------------------------------------------------------

    llama_data["choices"][0]["message"]["content"] = answer

    # Add structured RAG metadata.
    llama_data["rag"] = {
        "retrieved": len(documents),
        "filtered": len(candidates),
        "selected": len(selected_candidates),
        "unique_sources": len(unique_sources),
        "max_distance": MAX_DISTANCE
    }

    llama_data["sources"] = citations

    # --------------------------------------------------------
    # 12. Return OpenAI-compatible response
    # --------------------------------------------------------

    return llama_data