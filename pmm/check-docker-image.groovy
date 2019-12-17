library changelog: false, identifier: 'lib@JEN-Check-docker-images', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/hors/jenkins-pipelines'
]) _

void checkImageForDocker(String IMAGE){
     withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            TrityHightLog="$WORKSPACE/trivy-hight-pmm2-server.log"
            TrityCriticaltLog="$WORKSPACE/trivy-critical-pmm2-server.log"

            sg docker -c "
                docker login -u '${USER}' -p '${PASS}'
                /usr/local/bin/trivy -o \$TrityHightLog --ignore-unfixed --exit-code 0 --severity HIGH --quiet --auto-refresh perconalab/$IMAGE
                /usr/local/bin/trivy -o \$TrityCriticaltLog --ignore-unfixed --exit-code 0 --severity CRITICAL --quiet --auto-refresh perconalab/$IMAGE
            "

            if [ ! -s \$TrityHightLog ]; then
                rm -rf \$TrityHightLog
            fi

            if [ ! -s \$TrityCriticaltLog ]; then
                rm -rf \$TrityCriticaltLog
            fi
        """
    }
}
pipeline {
    parameters {
        string(
            defaultValue: 'pmm-server:dev-latest',
            description: 'Docker image name for PMM2 server',
            name: 'IMAGE_NAME')
    }
    agent {
         label 'min-centos-7-x64' 
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    stages {
        stage('Prepare') {
            steps {
                git branch: 'JEN-Check-docker-images', url: 'https://github.com/hors/jenkins-pipelines'
                installDocker()
                sh """
                    sudo yum -y install wget
                    TRIVY_VERSION=\$(curl --silent 'https://api.github.com/repos/aquasecurity/trivy/releases/latest' | grep '"tag_name":' | tr -d '"' | sed -E 's/.*v(.+),.*/\\1/')
                    wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz
                    sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz -C /usr/local/bin/
                """
            }
        }
        stage('Check PSMDB Docker images') {
            steps {
                checkImageForDocker("$IMAGE_NAME")
                sh '''
                   CRITICAL=$(ls trivy-critical-*) || true
                   if [ -n "$CRITICAL" ]; then
                       exit 1
                   fi
                '''
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: '*.log', allowEmptyArchive: true
            deleteDir()
        }
        failure {
            slackSend channel: '#UFK3F8SJZ', color: '#FF0000', message: "Check of PMM2 server docker images failed. Please check the log ${BUILD_URL}"
        }
    }
}
