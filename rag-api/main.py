from fastapi import FastApi
import requests
import chromadb
from sentence_transformers import SentenceTransformer

app=FastApi()

chroma_client=chromadb.HttpClient(host="192.168.100.92", port=8000)
embedder=SentenceTransformer('all-MiniLM-L6-v2')

@app.get("/health")
def health_check():
    return {"tsatus":"ok"}

@app.post("/ask")
def ask_question(payload:dict):
    question=payload.get("question")

    # 1. embed question
    question_embedding = embedder.encode(question).tolost()

    # tok k retrieval from chroma
    collection=chroma_client.get_collection(name="rag_collection")
    results=collection.query(query_embeddings=[question_embedding], n_results=4)

    #creating a prompt with contect (fallback if nothing is finded)
    context=" ".join(results['documents'][0] if results['documents'] else 'No relevant context!')
    prompt=f"Respond to question using this context: {context}\n Question:{question}"
    
    #calling llama server
    llama_url="http://192.168.100.92:8080/v1/chat/completions"
    data={"message": [{"role":"user", "context": prompt}]}

    response=requests.post(llama_url,json=data,timeout=30).json()

    return {
        "answer": response['choices'][0]['message']['content'],
        "sources": results['metadatas'][0]
    }