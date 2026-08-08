import requests
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
import chromadb
from sentence_transformers import SentenceTransformer

app = FastAPI(title="RAG-Ops API - Parent Document Retrieval")

# === CONFIGURARE ===
CHROMA_HOST = "localhost"
CHROMA_PORT = 8000
LLAMA_SERVER_URL = "http://localhost:8080/v1/chat/completions"

print("Se încarcă modelul de embedding pentru API...")
embedder = SentenceTransformer('all-MiniLM-L6-v2')

print("Se conectează la baza de date ChromaDB...")
chroma_client = chromadb.HttpClient(host=CHROMA_HOST, port=CHROMA_PORT)
collection = chroma_client.get_collection(name="rag_collection")


class AskRequest(BaseModel):
    question: str


@app.get("/health")
def health_check():
    return {"status": "RAG API is up and running!"}


@app.post("/ask")
def ask_question(req: AskRequest):
    # 1. Embed question (Vectorizarea întrebării)
    question_embedding = embedder.encode(req.question).tolist()

    # 2. Top-k retrieval din ChromaDB
    # Aducem top 3 cele mai relevante fragmente (chunk-uri)
    results = collection.query(
        query_embeddings=[question_embedding],
        n_results=3
    )

    if not results['documents'] or not results['documents'][0]:
        return {
            "answer": "Nu am găsit context relevant.",
            "sources": []
        }

    # 3. Construire context (Logica Parent Document Retrieval)
    # Folosim un dicționar pentru a deduplica fișierele părinte. 
    # Dacă 2 fragmente găsite fac parte din același script (ex: etl_pipeline.py),
    # vrem să trimitem codul sursă o singură dată către LLM, nu de două ori.
    parent_contexts = {}
    sources = []

    for metadata in results['metadatas'][0]:
        source_file = metadata.get('source_file', 'unknown')
        chunk_id = metadata.get('chunk_id', 'unknown')
        parent_text = metadata.get('parent_text', '')  # Extragem scriptul complet

        sources.append({"source_file": source_file, "chunk_id": chunk_id})

        if source_file not in parent_contexts:
            parent_contexts[source_file] = parent_text

    # Unim textele complete ale documentelor relevante
    full_context = "\n\n---\n\n".join([f"Fișier: {name}\nConținut:\n{text}" for name, text in parent_contexts.items()])

    # 4. Construiește promptul cu context
    # 4. Construiește promptul cu context
    # 4. Construiește promptul cu context (în engleză pentru stabilitate maximă)
    system_prompt = (
        "You are a Senior Data Engineer. Your task is to analyze the provided source code and "
        "provide detailed, clear, and professional explanations. Rely strictly on the given context. "
        "If asked about the role of a file, explain in detail what the code does, what functions "
        "or queries it contains, and what business logic it applies. "
        "If the answer is not in the context, clearly state: 'I could not find relevant context to answer.'"
    )

    user_prompt = f"""Please answer the following question with a high level of technical detail: {req.question}

    Use exclusively the following context extracted from my project:
    <context>
    {full_context}
    </context>

    Please structure the response clearly using paragraphs or lists to explain the steps and logic identified."""

    payload = {
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt}
        ],
        "temperature": 0.2,
        "frequency_penalty": 1.2,  # Penalizează repetițiile
        "presence_penalty": 0.5,  # Încurajează subiecte noi
        "max_tokens": 1024
    }

    # 5. Call llama-server cu timeout
    try:
        # Timeout fixat la 60 de secunde
        response = requests.post(LLAMA_SERVER_URL, json=payload, timeout=300)
        response.raise_for_status()

        data = response.json()
        answer = data['choices'][0]['message']['content']

        # 6. Răspuns JSON către utilizator
        return {
            "answer": answer,
            "sources": sources
        }

    except requests.exceptions.Timeout:
        raise HTTPException(status_code=504,
                            detail="Timeout: Modelul a durat prea mult să răspundă (peste 60 secunde).")
    except requests.exceptions.RequestException as e:
        raise HTTPException(status_code=500, detail=f"Eroare la comunicarea cu motorul LLM: {str(e)}")