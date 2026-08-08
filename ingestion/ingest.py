import os
import argparse
import datetime
import subprocess
import chromadb
from sentence_transformers import SentenceTransformer

# === CONFIGURARE ===
CHROMA_HOST = "localhost"
CHROMA_PORT = 8000
DOCS_DIR = os.path.join(os.path.dirname(__file__), "..", "docs")
BATCH_SIZE = 50  # Numărul maxim de fragmente trimise simultan

print("Se încarcă modelul de embedding...")
embedder = SentenceTransformer('all-MiniLM-L6-v2')

print(f"Se conectează la ChromaDB pe {CHROMA_HOST}:{CHROMA_PORT}...")
chroma_client = chromadb.HttpClient(host=CHROMA_HOST, port=CHROMA_PORT)
collection = chroma_client.get_or_create_collection(name="rag_collection")


def get_git_sha():
    try:
        sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=DOCS_DIR).decode('ascii').strip()
        return sha
    except Exception:
        return "unknown_commit"


def get_changed_files():
    try:
        output = subprocess.check_output(
            ['git', 'diff', '--name-only', 'HEAD~1', 'HEAD'],
            cwd=os.path.join(DOCS_DIR, "..")
        ).decode('ascii')

        changed = []
        for line in output.split('\n'):
            if line.startswith('docs/') and line.endswith(('.md', '.txt', '.py', '.sql', '.java')):
                changed.append(os.path.join(os.path.join(DOCS_DIR, ".."), line))
        return changed
    except Exception:
        return []


def chunk_text(text, chunk_size=400, overlap=50):
    chunks = []
    start = 0
    while start < len(text):
        end = start + chunk_size
        chunks.append(text[start:end])
        start += chunk_size - overlap
    return chunks


def process_file(filepath, git_sha):
    filename = os.path.basename(filepath)
    print(f"Procesare fișier: {filename}")

    with open(filepath, 'r', encoding='utf-8', errors='ignore') as f:
        full_text = f.read()

    chunks = chunk_text(full_text)
    updated_at = datetime.datetime.now().isoformat()

    ids = []
    documents = []
    metadatas = []
    embeddings = []

    for i, chunk in enumerate(chunks):
        chunk_id = f"{filename}_chunk_{i}"

        ids.append(chunk_id)
        documents.append(chunk)
        metadatas.append({
            "source_file": filename,
            "chunk_id": chunk_id,
            "updated_at": updated_at,
            "git_commit_sha": git_sha,
            "parent_text": full_text
        })
        embeddings.append(embedder.encode(chunk).tolist())

    # Upsert în baza de date vectorială folosind loturi (BATCHING)
    if ids:
        for i in range(0, len(ids), BATCH_SIZE):
            batch_ids = ids[i:i + BATCH_SIZE]
            collection.upsert(
                ids=batch_ids,
                documents=documents[i:i + BATCH_SIZE],
                metadatas=metadatas[i:i + BATCH_SIZE],
                embeddings=embeddings[i:i + BATCH_SIZE]
            )
        print(f"  -> Inserate {len(ids)} chunk-uri pentru {filename}.")


def main():
    parser = argparse.ArgumentParser(description="Ingestie documente pentru RAG")
    parser.add_argument("--changed-only", action="store_true", help="Indexează doar fișierele modificate recent")
    args = parser.parse_args()

    git_sha = get_git_sha()
    files_to_process = []

    # Ignorăm folderele de sistem care conțin librării uriașe
    ignored_folders = {'.venv', 'venv', '__pycache__', '.git', 'node_modules', '.idea'}

    if args.changed_only:
        print("Mod incremental activat: Se caută doar fișiere modificate...")
        files_to_process = get_changed_files()
    else:
        for root, dirs, files in os.walk(DOCS_DIR):
            # Tăiem din căutare folderele ignorate
            dirs[:] = [d for d in dirs if d not in ignored_folders]
            for file in files:
                if file.endswith(('.md', '.txt', '.py', '.sql', '.java')):
                    files_to_process.append(os.path.join(root, file))

    if not files_to_process:
        print("Nu s-au găsit documente valide pentru indexare.")
        return

    for filepath in files_to_process:
        process_file(filepath, git_sha)

    print("Ingestia s-a finalizat cu succes!")


if __name__ == "__main__":
    main()