import json
import requests

# Setul de întrebări și răspunsuri așteptate
GOLDEN_SET_URL = "http://10.0.2.2:8050/ask"
with open("eval/golden_set.json", "r") as f:
    golden_set = json.load(f)

hits = 0
for entry in golden_set:
    response = requests.post(GOLDEN_SET_URL, json={"question": entry["question"]}).json()
    # Verificăm dacă sursa corectă a fost găsită (exemplu de logică)
    if entry["expected_source"] in [s["source_file"] for s in response["sources"]]:
        hits += 1

hit_rate = hits / len(golden_set)
print(f"Retrieval Hit Rate: {hit_rate}")
if hit_rate < 0.7:
    exit(1) # Quality gate fail