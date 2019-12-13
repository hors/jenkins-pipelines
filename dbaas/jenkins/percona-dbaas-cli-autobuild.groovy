library changelog: false, identifier: 'lib@CLOUD-476-create-build-inf-dbaas', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/hors/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'docker'
    }
    parameters {
        string(
            defaultValue: 'CLOUD-476-create-build-inf-dbaas',
            description: 'Tag/Branch for percona-dbaas-cli repository',
            name: 'GIT_BRANCH')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    environment {
        GIT_REPO = 'http://github.com/Percona-Lab/percona-dbaas-cli'
    }
    stages {
        stage('Prepare') {
            steps {
                git branch: 'CLOUD-476-create-build-inf-dbaas', url: 'https://github.com/Percona-Lab/percona-dbaas-cli.git'
                sh '''
                    # sudo is needed for better node recovery after compilation failure
                    # if building failed on compilation stage directory will have files owned by docker user
                    sudo git reset --hard
                    sudo git clean -xdf
                '''
            }
        }
        stage('Build bdaas source') {
            steps {
                sh '''
                    sg docker -c "
                        build/bin/build-source
                    "
                '''
                stash includes: 'results/source_tarball/*.tar.*', name: 'source.tarball'
                archiveArtifacts 'results/source_tarball/*.tar.*'
           //     uploadTarball('source')
            }
        }
        stage('Build bdaas binary') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '2a84aea7-32a0-4598-9e8d-5153179097a9', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        sg docker -c "
                            build/bin/build-binary
                        "
                    '''
                }
                archiveArtifacts 'results/tarball/*.tar.*'
           //     uploadTarball('source')
            }
        }
    }
    post {
        always {
            sh '''
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}
