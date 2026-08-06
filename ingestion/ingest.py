import chromadb
from sentence_transformers import SentenceTransformer
import os

# se conecteaza la chromadb
chroma_client=chromadb.HttpClient(host="192.168.100.92", port=8000)
collection=chroma_client.get_or_create_collection(name="rag_collection")

#incarc modelul de embeddings
embedder=SentenceTransformer('all-MiniLM-L6-v2')

def process_and_ingest():
    docs_path="../docs/datawarehouse"

    #pana ce e MVP ultorior va fi logica de citire a fisierelor si procesul de chunking
    # si de a face upsert in ChromaDB cu metadate

    print ("Start indexing docs...")

if __name__=="__main__":
    process_and_ingest()