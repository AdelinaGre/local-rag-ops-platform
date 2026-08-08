import os
import re
import argparse
import datetime
import subprocess
import chromadb
from sentence_transformers import SentenceTransformer

# === CONFIGURARE ===
CHROMA_HOST = "10.0.2.2"
CHROMA_PORT = 8000
DOCS_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
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
            cwd=DOCS_DIR
        ).decode('ascii')

        changed = []
        for line in output.split('\n'):
            line = line.strip()
            if line and line.endswith(('.md', '.txt', '.py', '.sql', '.java', '.yml', '.yaml', '.gradle')):
                full_path = os.path.join(DOCS_DIR, line)
                if os.path.exists(full_path):
                    changed.append(full_path)
        return changed
    except Exception:
        return []


def semantic_chunking(text, filename, max_size=800, overlap_size=150):
    """
    Împarte textul bazat pe limite semantice (paragrafe / funcții)
    și aplică un overlap curat.
    """
    # 1. Determinare metadate limbaj
    ext = os.path.splitext(filename)[1].lower()
    language_map = {'.py': 'python', '.java': 'java', '.md': 'markdown', '.sql': 'sql', '.txt': 'text'}
    language = language_map.get(ext, 'unknown')

    # 2. Normalizare: colapsăm liniile goale multiple, dar păstrăm indentarea
    text = re.sub(r'\n{3,}', '\n\n', text)

    # 3. Tăiere semantică naturală (funcțiile/clasele sunt despărțite de \n\n)
    blocks = text.split('\n\n')

    chunks = []
    current_chunk_blocks = []
    current_length = 0

    for block in blocks:
        block_len = len(block)

        # Dacă block-ul curent depășește limita și avem deja date în chunk
        if current_length + block_len > max_size and current_chunk_blocks:
            chunk_text = '\n\n'.join(current_chunk_blocks)
            chunks.append({"text": chunk_text, "language": language})

            # Construim Overlap-ul: luăm ultimele block-uri care se încadrează în limita de overlap
            overlap_blocks = []
            overlap_length = 0
            for prev_block in reversed(current_chunk_blocks):
                if overlap_length + len(prev_block) <= overlap_size:
                    overlap_blocks.insert(0, prev_block)
                    overlap_length += len(prev_block) + 2  # +2 pentru \n\n
                else:
                    break

            current_chunk_blocks = overlap_blocks
            current_length = overlap_length

        current_chunk_blocks.append(block)
        current_length += block_len + 2

    # Adăugăm ce a rămas
    if current_chunk_blocks:
        chunks.append({"text": '\n\n'.join(current_chunk_blocks), "language": language})

    return chunks


def process_file(filepath, git_sha):
    filename = os.path.basename(filepath)
    print(f"Procesare fișier: {filename}")

    try:
        with open(filepath, 'r', encoding='utf-8', errors='ignore') as f:
            full_text = f.read()
    except Exception as e:
        print(f"  -> Eroare la citire {filename}: {e}")
        return

    # Evităm fișierele binare sau complet goale
    if not full_text.strip() or '\x00' in full_text:
        print(f"  -> Sărit (binar/gol): {filename}")
        return

    chunk_data = semantic_chunking(full_text, filename)
    updated_at = datetime.datetime.now().isoformat()

    ids = []
    documents = []
    metadatas = []
    embeddings = []

    for i, c_data in enumerate(chunk_data):
        chunk_text = c_data["text"]
        if not chunk_text.strip():
            continue

        chunk_id = f"{filename}_chunk_{i}"

        ids.append(chunk_id)
        documents.append(chunk_text)
        metadatas.append({
            "source_file": filename,
            "language": c_data["language"],
            "chunk_index": i,
            "chunk_id": chunk_id,
            "updated_at": updated_at,
            "git_commit_sha": git_sha
        })
        embeddings.append(embedder.encode(chunk_text).tolist())

    # Upsert în baza de date vectorială folosind loturi
    if ids:
        for i in range(0, len(ids), BATCH_SIZE):
            batch_ids = ids[i:i + BATCH_SIZE]
            collection.upsert(
                ids=batch_ids,
                documents=documents[i:i + BATCH_SIZE],
                metadatas=metadatas[i:i + BATCH_SIZE],
                embeddings=embeddings[i:i + BATCH_SIZE]
            )
        print(f"  -> Inserate {len(ids)} chunk-uri semantice pentru {filename}.")


def main():
    parser = argparse.ArgumentParser(description="Ingestie documente pentru RAG")
    parser.add_argument("--changed-only", action="store_true", help="Indexează doar fișierele modificate recent")
    args = parser.parse_args()

    git_sha = get_git_sha()
    files_to_process = []

    ignored_folders = {'.venv', 'venv', '__pycache__', '.git', 'node_modules', '.idea', '.gradle', 'build'}

    if args.changed_only:
        print("Mod incremental activat: Se caută doar fișiere modificate...")
        files_to_process = get_changed_files()
    else:
        for root, dirs, files in os.walk(DOCS_DIR):
            dirs[:] = [d for d in dirs if d not in ignored_folders]
            for file in files:
                if file.endswith(('.md', '.txt', '.py', '.sql', '.java', '.yml', '.yaml', '.gradle')):
                    files_to_process.append(os.path.join(root, file))

    if not files_to_process:
        print("Nu s-au găsit documente valide pentru indexare.")
        return

    for filepath in files_to_process:
        process_file(filepath, git_sha)

    print("Ingestia s-a finalizat cu succes!")


if __name__ == "__main__":
    main()