import os
import re
import argparse
import datetime
import subprocess
import hashlib
from collections import Counter

import chromadb
from sentence_transformers import SentenceTransformer

# === CONFIGURARE ===
CHROMA_HOST = "10.0.2.2"
CHROMA_PORT = 8000
BATCH_SIZE = 50

ALLOWED_EXTENSIONS = {
    ".md", ".txt", ".py", ".sql", ".java",
    ".yml", ".yaml", ".gradle"
}
ALLOWED_FILENAMES = {
    "docker-compose.yml",
    "Dockerfile"
}
IGNORED_FOLDERS = {
    ".venv", "venv", "__pycache__", ".git", "node_modules",
    ".idea", ".gradle", "build", "target", ".mvn"
}


def get_repo_root():
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "--show-toplevel"],
            cwd=os.path.dirname(__file__)
        ).decode("utf-8").strip()
    except Exception:
        return os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


DOCS_DIR = get_repo_root()

print("Se încarcă modelul de embedding...")
embedder = SentenceTransformer("all-MiniLM-L6-v2")

print(f"Se conectează la ChromaDB pe {CHROMA_HOST}:{CHROMA_PORT}...")
chroma_client = chromadb.HttpClient(host=CHROMA_HOST, port=CHROMA_PORT)
collection = chroma_client.get_or_create_collection(name="rag_collection")


def get_git_sha():
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=DOCS_DIR
        ).decode("ascii").strip()
    except Exception:
        return "unknown_commit"


def is_allowed_file(file_name: str) -> bool:
    ext = os.path.splitext(file_name)[1].lower()
    return (ext in ALLOWED_EXTENSIONS) or (file_name in ALLOWED_FILENAMES)


def get_changed_files():
    try:
        output = subprocess.check_output(
            ["git", "diff", "--name-only", "HEAD~1", "HEAD"],
            cwd=DOCS_DIR
        ).decode("utf-8", errors="ignore")

        changed = []
        for line in output.splitlines():
            rel = line.strip()
            if not rel:
                continue

            abs_path = os.path.join(DOCS_DIR, rel)
            base_name = os.path.basename(abs_path)

            if os.path.exists(abs_path) and is_allowed_file(base_name):
                changed.append(abs_path)

        return sorted(set(changed))
    except Exception:
        return []


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


def semantic_chunking(text, filename, max_size=None, overlap_size=None):
    ext = os.path.splitext(filename)[1].lower()
    if ext in [".java", ".py", ".sql"]:
        max_size = 350
        overlap_size = 75
    else:
        max_size = 800
        overlap_size = 150
    language_map = {
        ".py": "python",
        ".java": "java",
        ".md": "markdown",
        ".sql": "sql",
        ".txt": "text",
        ".yml": "yaml",
        ".yaml": "yaml",
        ".gradle": "gradle",
    }
    language = language_map.get(ext, "unknown")

    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = re.sub(r"\n{3,}", "\n\n", text)

    blocks = [b for b in text.split("\n\n") if b.strip()]

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
    rel_path = os.path.relpath(filepath, DOCS_DIR).replace(os.sep, "/")
    filename = os.path.basename(filepath)
    print(f"Procesare fișier: {rel_path}")

    try:
        with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
            full_text = f.read()
    except Exception as e:
        print(f"  -> Eroare la citire {rel_path}: {e}")
        return 0, "read_error"

    is_empty = not full_text.strip()
    has_nul = "\x00" in full_text

    if is_empty or has_nul:
        print(f"  -> Sărit (binar/gol): {rel_path} | len={len(full_text)} | nul={has_nul}")
        return 0, "empty_or_binary"

    if len(full_text) < 1200:
        chunk_data = [{
            "text": full_text,
            "language": os.path.splitext(filename)[1].lower()
        }]
    else:
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
        document_for_embedding = f"""
        FILE_PATH:
        {rel_path}

        FILE_NAME:
        {filename}

        CONTENT:
        {chunk_text}
        """.strip()

        documents.append(document_for_embedding)
        metadatas.append({
            "source_file": filename,
            "source_path": rel_path,
            "file_extension": os.path.splitext(filename)[1].lower(),
            "filename_lower": filename.lower(),
            "language": c_data["language"],
            "chunk_index": i,
            "chunk_id": chunk_id,
            "chunk_size_chars": len(chunk_text),
            "updated_at": updated_at,
            "git_commit_sha": git_sha,
            "branch": os.getenv("BRANCH_NAME", "local"),
            "build_number": os.getenv("BUILD_NUMBER", "local")
        })
        embeddings.append(
            embedder.encode(document_for_embedding).tolist()
        )

    if not ids:
        print(f"  -> Sărit (fără chunk-uri valide): {rel_path}")
        return 0, "no_valid_chunks"

    for i in range(0, len(ids), BATCH_SIZE):
        batch_ids = ids[i:i + BATCH_SIZE]
        collection.upsert(
            ids=batch_ids,
            documents=documents[i:i + BATCH_SIZE],
            metadatas=metadatas[i:i + BATCH_SIZE],
            embeddings=embeddings[i:i + BATCH_SIZE]
        )

    print(f"  -> Inserate {len(ids)} chunk-uri semantice pentru {rel_path}.")
    return len(ids), "indexed"


def discover_all_files():
    files_to_process = []
    ext_counter = Counter()

    for root, dirs, files in os.walk(DOCS_DIR):
        dirs[:] = sorted([d for d in dirs if d not in IGNORED_FOLDERS])

        for file_name in sorted(files):
            if not is_allowed_file(file_name):
                continue

            abs_path = os.path.join(root, file_name)
            files_to_process.append(abs_path)

            ext = os.path.splitext(file_name)[1].lower()
            ext_counter[ext if ext else file_name] += 1

    return sorted(set(files_to_process)), ext_counter


def main():
    parser = argparse.ArgumentParser(description="Ingestie documente pentru RAG")
    parser.add_argument(
        "--changed-only",
        action="store_true",
        help="Indexează doar fișierele modificate recent"
    )
    args = parser.parse_args()

    git_sha = get_git_sha()
    print(f"Repo root detectat: {DOCS_DIR}")
    print(f"Git commit SHA: {git_sha}")

    total_scanned = 0
    total_indexed_files = 0
    total_chunks = 0
    skip_reasons = Counter()
    ext_counter = Counter()

    if args.changed_only:
        print("Mod incremental activat: Se caută doar fișiere modificate...")
        files_to_process = get_changed_files()
        for p in files_to_process:
            ext = os.path.splitext(p)[1].lower()
            ext_counter[ext if ext else os.path.basename(p)] += 1
    else:
        files_to_process, ext_counter = discover_all_files()

    if not files_to_process:
        print("Nu s-au găsit documente valide pentru indexare.")
        return

    print(f"Total fișiere candidate: {len(files_to_process)}")
    print("Top extensii candidate:", dict(ext_counter))
    print("Primele 20 fișiere candidate:")
    for p in files_to_process[:20]:
        print(" -", os.path.relpath(p, DOCS_DIR).replace(os.sep, "/"))

    for filepath in files_to_process:
        total_scanned += 1
        chunks_written, status = process_file(filepath, git_sha)

        if status == "indexed":
            total_indexed_files += 1
            total_chunks += chunks_written
        else:
            skip_reasons[status] += 1

    print("Ingestia s-a finalizat cu succes!")
    print(
        f"Rezumat ingestie: scanned={total_scanned}, "
        f"indexed_files={total_indexed_files}, total_chunks={total_chunks}, "
        f"skipped={sum(skip_reasons.values())}"
    )
    if skip_reasons:
        print(f"Detalii skip: {dict(skip_reasons)}")


if __name__ == "__main__":
    main()