import os
import re
import argparse
import datetime
import subprocess
import hashlib
import chromadb
from sentence_transformers import SentenceTransformer

# === CONFIGURARE ===
CHROMA_HOST = "10.0.2.2"
CHROMA_PORT = 8000
DOCS_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
BATCH_SIZE = 50  # Numărul maxim de fragmente trimise simultan

print("Se încarcă modelul de embedding...")
embedder = SentenceTransformer("all-MiniLM-L6-v2")

print(f"Se conectează la ChromaDB pe {CHROMA_HOST}:{CHROMA_PORT}...")
chroma_client = chromadb.HttpClient(host=CHROMA_HOST, port=CHROMA_PORT)
collection = chroma_client.get_or_create_collection(name="rag_collection")


def get_git_sha():
    try:
        sha = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=DOCS_DIR
        ).decode("ascii").strip()
        return sha
    except Exception:
        return "unknown_commit"


def get_changed_files():
    try:
        output = subprocess.check_output(
            ["git", "diff", "--name-only", "HEAD~1", "HEAD"],
            cwd=DOCS_DIR
        ).decode("ascii")

        changed = []
        for line in output.split("\n"):
            line = line.strip()
            if line and line.endswith((".md", ".txt", ".py", ".sql", ".java")):
                full_path = os.path.join(DOCS_DIR, line)
                if os.path.exists(full_path):
                    changed.append(full_path)
        return sorted(set(changed))
    except Exception:
        return []


def semantic_chunking(text, filename, max_size=800, overlap_size=150):
    """
    Împarte textul bazat pe limite semantice (paragrafe / funcții)
    și aplică un overlap curat.
    Include fallback pentru blocuri foarte mari.
    """
    ext = os.path.splitext(filename)[1].lower()
    language_map = {
        ".py": "python",
        ".java": "java",
        ".md": "markdown",
        ".sql": "sql",
        ".txt": "text"
    }
    language = language_map.get(ext, "unknown")

    text = re.sub(r"\n{3,}", "\n\n", text)

    blocks = [b for b in text.split("\n\n") if b.strip()]

    def split_oversized_block(block: str, hard_limit: int):
        if len(block) <= hard_limit:
            return [block]

        parts = re.split(r"(?<=[\.\!\?])\s+|\n", block)
        parts = [p for p in parts if p and p.strip()]

        out = []
        cur = []
        cur_len = 0

        for p in parts:
            p_len = len(p)
            if cur_len + p_len + 1 > hard_limit and cur:
                out.append(" ".join(cur))
                cur = [p]
                cur_len = p_len
            else:
                cur.append(p)
                cur_len += p_len + 1

        if cur:
            out.append(" ".join(cur))

        final_out = []
        for seg in out:
            if len(seg) <= hard_limit:
                final_out.append(seg)
            else:
                for i in range(0, len(seg), hard_limit):
                    final_out.append(seg[i:i + hard_limit])

        return final_out

    chunks = []
    current_chunk_blocks = []
    current_length = 0

    for raw_block in blocks:
        sub_blocks = split_oversized_block(raw_block, max_size)

        for block in sub_blocks:
            block_len = len(block)

            if current_length + block_len > max_size and current_chunk_blocks:
                chunk_text = "\n\n".join(current_chunk_blocks)
                chunks.append({"text": chunk_text, "language": language})

                overlap_blocks = []
                overlap_length = 0

                for prev_block in reversed(current_chunk_blocks):
                    candidate_len = len(prev_block) + (2 if overlap_blocks else 0)
                    if overlap_length + candidate_len <= overlap_size:
                        overlap_blocks.insert(0, prev_block)
                        overlap_length += candidate_len
                    else:
                        break

                current_chunk_blocks = overlap_blocks
                current_length = overlap_length

            current_chunk_blocks.append(block)
            current_length += block_len + 2

    if current_chunk_blocks:
        chunks.append({"text": "\n\n".join(current_chunk_blocks), "language": language})

    return chunks


def process_file(filepath, git_sha):
    filename = os.path.basename(filepath)
    rel_path = os.path.relpath(filepath, DOCS_DIR).replace(os.sep, "/")
    print(f"Procesare fișier: {rel_path}")

    try:
        with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
            full_text = f.read()
    except Exception as e:
        print(f"  -> Eroare la citire {rel_path}: {e}")
        return 0

    if not full_text.strip() or "\x00" in full_text:
        print(f"  -> Sărit (binar/gol): {rel_path}")
        return 0

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

        stable_key = f"{rel_path}::{i}"
        short_hash = hashlib.sha1(stable_key.encode("utf-8")).hexdigest()[:12]
        chunk_id = f"{rel_path}::chunk_{i}::{short_hash}"

        ids.append(chunk_id)
        documents.append(chunk_text)
        metadatas.append({
            "source_file": filename,      # compatibilitate
            "source_path": rel_path,      # util pentru evaluare și debugging
            "language": c_data["language"],
            "chunk_index": i,
            "chunk_id": chunk_id,
            "chunk_size_chars": len(chunk_text),
            "updated_at": updated_at,
            "git_commit_sha": git_sha
        })
        embeddings.append(embedder.encode(chunk_text).tolist())

    if ids:
        for i in range(0, len(ids), BATCH_SIZE):
            batch_ids = ids[i:i + BATCH_SIZE]
            collection.upsert(
                ids=batch_ids,
                documents=documents[i:i + BATCH_SIZE],
                metadatas=metadatas[i:i + BATCH_SIZE],
                embeddings=embeddings[i:i + BATCH_SIZE]
            )
        print(f"  -> Inserate {len(ids)} chunk-uri semantice pentru {rel_path}.")
        return len(ids)

    return 0


def main():
    parser = argparse.ArgumentParser(description="Ingestie documente pentru RAG")
    parser.add_argument(
        "--changed-only",
        action="store_true",
        help="Indexează doar fișierele modificate recent"
    )
    args = parser.parse_args()

    git_sha = get_git_sha()
    files_to_process = []

    ignored_folders = {
        ".venv", "venv", "__pycache__", ".git", "node_modules",
        ".idea", ".gradle", "build"
    }

    total_scanned = 0
    total_indexed_files = 0
    total_chunks = 0
    skipped_files = 0

    if args.changed_only:
        print("Mod incremental activat: Se caută doar fișiere modificate...")
        files_to_process = get_changed_files()
    else:
        for root, dirs, files in os.walk(DOCS_DIR):
            dirs[:] = sorted([d for d in dirs if d not in ignored_folders])
            for file in sorted(files):
                if file.endswith((".md", ".txt", ".py", ".sql", ".java")):
                    files_to_process.append(os.path.join(root, file))

    files_to_process = sorted(set(files_to_process))

    if not files_to_process:
        print("Nu s-au găsit documente valide pentru indexare.")
        return

    for filepath in files_to_process:
        total_scanned += 1
        chunks_written = process_file(filepath, git_sha)
        if chunks_written > 0:
            total_indexed_files += 1
            total_chunks += chunks_written
        else:
            skipped_files += 1

    print("Ingestia s-a finalizat cu succes!")
    print(
        f"Rezumat ingestie: scanned={total_scanned}, "
        f"indexed_files={total_indexed_files}, skipped={skipped_files}, total_chunks={total_chunks}"
    )


if __name__ == "__main__":
    main()