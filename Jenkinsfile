pipeline {
    agent any

    environment {
        PYTHON = "./.venv/bin/python"
        PIP = "./.venv/bin/pip"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
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
            steps {
                sh "${PYTHON} ingestion/ingest.py"
            }
        }

        stage('Evaluation') {
            steps {
                sh "${PYTHON} eval/run_eval.py"
            }
        }

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

min_source_hit = 0.85
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