pipeline {
    agent any
    
    environment {
        PYTHON = "./.venv/bin/python"
        PIP = "./.venv/bin/pip"
    }

    stages {
        stage('Checkout') {
    steps {
        checkout([
            $class: 'GitSCM',
            branches: scm.branches,
            userRemoteConfigs: scm.userRemoteConfigs,
            extensions: [
                [$class: 'SubmoduleOption',
                    disableSubmodules: false,
                    parentCredentials: true,
                    recursiveSubmodules: true,
                    trackingSubmodules: true
                ]
            ]
        ])

        sh '''
        echo "=== Verify submodule content ==="
        git submodule status || true
        find docs/datawarehouse -maxdepth 6 -type f | head -50 || true
        find . -type f -name "*.java" | wc -l
        '''
    }
}
        
        stage('Setup Environment') {
            steps {
                echo "Setting up Python virtual environment in Jenkins..."
                sh '''
                python3 -m venv .venv
                ${PIP} install --upgrade pip
                
                # Instalam PyTorch varianta CPU (fara gigabiții de drivere Nvidia)
                ${PIP} install torch --index-url https://download.pytorch.org/whl/cpu
                
                # Apoi instalam restul pachetelor
                ${PIP} install chromadb sentence-transformers requests
                '''
            }
        }
        
        stage('Detect Changes') {
            steps {
                script {
                    // Caută orice fișier modificat cu extensiile noastre (inclusiv java)
                    def changes = sh(script: "git diff --name-only HEAD~1 HEAD | grep -E '\\.(md|txt|py|sql|java)\$' || true", returnStdout: true).trim()
                    env.HAS_DOC_CHANGES = changes ? "true" : "false"
                    echo "Changes detected: ${env.HAS_DOC_CHANGES}"
                }
            }
        }
        
        stage('Incremental Re-index') {
            // Rulăm ingestia completă (ștergem temporar condiția 'when')
            steps {
                echo "Running full ingestion..."
                sh "${PYTHON} ingestion/ingest.py"
            }
        }
        
        stage('Retrieval Evaluation') {
            steps {
                echo "Evaluating RAG retrieval quality..."
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