pipeline {
    agent any

    environment {
        PYTHON = "./.venv/bin/python"
        PIP = "./.venv/bin/pip"

        // PR profile defaults
        MIN_SOURCE_HIT = "0.70"
        MIN_KEYWORD_RECALL = "0.78"
        MAX_EMPTY_CONTEXT = "0.10"

        EVAL_TOPK_RETRIEVE = "12"
	EVAL_TOPK_HIT = "8"

    }

    stages {
        stage('Checkout') {
            steps { checkout scm }
        }

        stage('Setup Environment') {
            steps {
                sh '''
                python3 -m venv .venv
                ${PIP} install --upgrade pip
                ${PIP} install torch --index-url https://download.pytorch.org/whl/cpu
                ${PIP} install chromadb sentence-transformers requests
                '''
            }
        }

        stage('Re-index') {
            steps { sh "${PYTHON} ingestion/ingest.py" }
        }

        stage('Evaluation') {
            steps { sh "${PYTHON} eval/run_eval.py" }
        }

        stage('Quality Gate') {
            steps {
                sh '''
                ${PYTHON} - << 'PY'
import json, os, sys
from pathlib import Path

p = Path("eval/results.json")
if not p.exists():
    print("Missing eval/results.json")
    sys.exit(1)

d = json.loads(p.read_text(encoding="utf-8"))

min_source = float(os.getenv("MIN_SOURCE_HIT", "0.65"))
min_kw = float(os.getenv("MIN_KEYWORD_RECALL", "0.75"))
max_empty = float(os.getenv("MAX_EMPTY_CONTEXT", "0.10"))

source = float(d.get("source_hit_rate", 0.0))
kw = float(d.get("answer_keyword_recall", 0.0))
empty = float(d.get("empty_context_rate", 1.0))

print(f"source_hit_rate={source}")
print(f"answer_keyword_recall={kw}")
print(f"empty_context_rate={empty}")
print(f"thresholds: min_source={min_source}, min_kw={min_kw}, max_empty={max_empty}")

if source < min_source or kw < min_kw or empty > max_empty:
    print("Quality gate failed!")
    sys.exit(1)

print("Quality gate passed.")
PY
                '''
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: 'eval/results.json', fingerprint: true
        }
        failure {
            echo "RAG-Ops Pipeline Failed!"
        }
    }
}