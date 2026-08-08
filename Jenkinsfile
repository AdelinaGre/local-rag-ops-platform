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
                script {
                    def results = readJSON file: 'eval/results.json'

                    def minSourceHit = 0.80
                    def minKeywordRecall = 0.70
                    def maxEmptyContext = 0.10

                    echo "source_hit_rate=${results.source_hit_rate}"
                    echo "answer_keyword_recall=${results.answer_keyword_recall}"
                    echo "empty_context_rate=${results.empty_context_rate}"

                    if (results.source_hit_rate < minSourceHit ||
                        results.answer_keyword_recall < minKeywordRecall ||
                        results.empty_context_rate > maxEmptyContext) {
                        error("Quality gate failed!")
                    }

                    echo "Quality gate passed."
                }
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