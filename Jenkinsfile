stage('Quality Gate') {
    steps {
        sh '''
        ${PYTHON} - << 'PY'
import json, sys
from pathlib import Path

p = Path("eval/results.json")
if not p.exists():
    print("Missing eval/results.json")
    sys.exit(1)

data = json.loads(p.read_text(encoding="utf-8"))

min_source_hit = 0.80
min_keyword_recall = 0.70
max_empty_context = 0.10

source_hit = float(data.get("source_hit_rate", 0.0))
kw_recall = float(data.get("answer_keyword_recall", 0.0))
empty_rate = float(data.get("empty_context_rate", 1.0))

print(f"source_hit_rate={source_hit}")
print(f"answer_keyword_recall={kw_recall}")
print(f"empty_context_rate={empty_rate}")

if source_hit < min_source_hit or kw_recall < min_keyword_recall or empty_rate > max_empty_context:
    print("Quality gate failed!")
    sys.exit(1)

print("Quality gate passed.")
PY
        '''
    }
}