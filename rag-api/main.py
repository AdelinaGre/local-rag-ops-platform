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

CHROMA_HOST = os.getenv("CHROMA_HOST", "chromadb")
CHROMA_PORT = int(os.getenv("CHROMA_PORT", "8000"))

LLAMA_API_URL = os.getenv(
    "LLAMA_BASE_URL",
    "http://llama-server:8080/v1"
)

TOP_K = int(os.getenv("TOP_K", "5"))


# ============================================================
# INITIALIZATION
# ============================================================

print("Se încarcă modelul de embedding...")

embedder = SentenceTransformer("all-MiniLM-L6-v2")

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
# HEALTH CHECK
# ============================================================

@app.get("/health")
def health():
    return {
        "status": "ok",
        "role": "RAG Orchestrator"
    }


# ============================================================
# OPENAI-COMPATIBLE CHAT ENDPOINT
# ============================================================

@app.post("/v1/chat/completions")
def chat_completions(req: ChatRequest):

    # --------------------------------------------------------
    # 1. Get latest user message
    # --------------------------------------------------------

    user_messages = [
        msg for msg in req.messages
        if msg.role == "user"
    ]

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

    query_embedding = embedder.encode(
        user_query
    ).tolist()


    # --------------------------------------------------------
    # 3. Search ChromaDB
    # --------------------------------------------------------

    results = collection.query(
        query_embeddings=[query_embedding],
        n_results=TOP_K
    )

    print("\n========== CHROMA RESULTS ==========")
    print("Documents:", results.get("documents"))
    print("Metadata:", results.get("metadatas"))
    print("Distances:", results.get("distances"))
    print("====================================\n")


    # --------------------------------------------------------
    # 4. Build retrieved context
    # --------------------------------------------------------

    context_parts = []
    citations = []

    if (
        results
        and results.get("documents")
        and results["documents"][0]
    ):

        for i, doc in enumerate(results["documents"][0]):

            metadata = (
                results["metadatas"][0][i]
                if results.get("metadatas")
                and results["metadatas"][0]
                else {}
            )

            source_file = metadata.get(
                "source_file",
                "Unknown"
            )

            context_parts.append(
                f'<source id="{i + 1}">\n'
                f"Source: {source_file}\n"
                f"{doc}\n"
                f"</source>"
            )

            citations.append(source_file)


    context_text = "\n\n".join(context_parts)


    # --------------------------------------------------------
    # 5. Build RAG prompt
    # --------------------------------------------------------

    if context_text:

        augmented_prompt = f"""
You are a strict technical assistant.

Answer the user's question using the retrieved context.

Rules:
- Use the retrieved context as the primary source.
- Do not invent information.
- If the answer is not present in the context, clearly say that the information is not available in the retrieved documents.
- Cite sources using [id] when the source has an id attribute.
- Answer in the same language as the user.

<CONTEXT>
{context_text}
</CONTEXT>

USER QUESTION:
{user_query}
"""

        req.messages[-1].content = augmented_prompt

    else:

        req.messages[-1].content = f"""
You are a strict technical assistant.

The retrieval system did not find any relevant documents.

Answer only if you can do so reliably. Otherwise clearly state that
the information is not available in the retrieved documents.

USER QUESTION:
{user_query}
"""


    # --------------------------------------------------------
    # 6. Prepare request for llama.cpp
    # --------------------------------------------------------

    payload = req.model_dump(exclude_none=True)

    # Disable streaming for now.
    # This makes the integration with Open WebUI simpler.
    payload["stream"] = False


    print("\n========== LLAMA REQUEST ==========")
    print("LLAMA URL:", LLAMA_API_URL)
    print("MODEL:", payload.get("model"))
    print("===================================\n")


    # --------------------------------------------------------
    # 7. Send request to llama.cpp
    # --------------------------------------------------------

    try:

        resp = requests.post(
            f"{LLAMA_API_URL}/chat/completions",
            json=payload,
            timeout=300
        )

        resp.raise_for_status()

        llama_data = resp.json()

    except Exception as e:

        print("ERROR communicating with llama.cpp:")
        print(e)

        raise HTTPException(
            status_code=500,
            detail=(
                "Error communicating with llama.cpp: "
                f"{str(e)}"
            )
        )


    # --------------------------------------------------------
    # 8. Add source citations
    # --------------------------------------------------------

    if citations:

        citation_text = (
            "\n\n**Surse folosite:** "
            + ", ".join(citations)
        )

        llama_data["choices"][0]["message"]["content"] += (
            citation_text
        )


    # --------------------------------------------------------
    # 9. Return OpenAI-compatible response
    # --------------------------------------------------------

    return llama_data