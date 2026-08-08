import json
import requests
import sys

# URL-ul adaptat pentru a rula din interiorul Jenkins către Windows
GOLDEN_SET_URL = "http://10.0.2.2:8050/ask"
DATASET_PATH = "eval/dataset.json"

try:
    with open(DATASET_PATH, "r", encoding="utf-8") as f:
        golden_set = json.load(f)
except FileNotFoundError:
    print(f"Eroare: Nu am putut găsi fișierul {DATASET_PATH}")
    sys.exit(1)

hits = 0
for entry in golden_set:
    response = requests.post(GOLDEN_SET_URL, json={"question": entry["question"]}).json()

    # Extragem lista de fișiere returnate de ChromaDB
    returned_sources = [s["source_file"] for s in response.get("sources", [])]
    expected_sources = entry["expected_sources"]

    # Verificăm dacă MĂCAR UNA din sursele așteptate se află în rezultate (Top-K hit)
    success = any(expected in returned_sources for expected in expected_sources)

    if success:
        hits += 1
    else:
        difficulty = entry.get("difficulty", "unknown").upper()
        print(f"ERROR [{difficulty}]: '{entry['question']}'")
        print(f"   Așteptam una din sursele: {expected_sources}")
        print(f"   Dar modelul a returnat: {returned_sources}\n")

hit_rate = hits / len(golden_set)
print(f"\nRetrieval Hit Rate: {hit_rate:.2f} ({hits}/{len(golden_set)})")

# Pentru un dataset matur, 80% este un prag de producție excelent
if hit_rate < 0.8:
    print("Quality gate failed! Hit rate under 0.80")
    sys.exit(1)