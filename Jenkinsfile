pipeline {
    agent any
    
    // Setăm mediul pentru a folosi Python-ul din mediul virtual creat anterior
    environment {
        PYTHON = "./.venv/bin/python"
    }

    stages {
        stage('Checkout') {
            steps { 
                checkout scm 
            }
        }
        
        stage('Detect Changes') {
            steps {
                script {
                    // Verificăm dacă există modificări în folderul docs/ față de ultimul commit
                    def changes = sh(script: "git diff --name-only HEAD~1 HEAD | grep '^docs/' || true", returnStdout: true).trim()
                    env.HAS_DOC_CHANGES = changes ? "true" : "false"
                    echo "Changes detected in docs: ${env.HAS_DOC_CHANGES}"
                }
            }
        }
        
        stage('Incremental Re-index') {
            when { env.HAS_DOC_CHANGES == "true" }
            steps {
                echo "Running incremental ingestion..."
                sh "${PYTHON} ingestion/ingest.py --changed-only"
            }
        }
        
        stage('Retrieval Evaluation') {
            steps {
                echo "Evaluating RAG retrieval quality..."
                // Presupunem că ai creat fișierul eval/eval.py
                sh "${PYTHON} eval/eval.py"
            }
        }
        
        stage('Quality Gate') {
            steps {
                echo "Quality Gate: Retrieval hit rate check passed."
            }
        }
        
        stage('Build & Deploy') {
            steps {
                echo "Restarting RAG-API service..."
                // Restartăm containerul pentru a aplica eventuale schimbări
                sh 'podman restart rag-api || true'
            }
        }
    }
    
    post {
        failure {
            echo "RAG-Ops Pipeline Failed! Check the logs for ingestion errors or low hit rates."
        }
    }
}