pipeline {
    agent {
         label 'docker'
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', artifactDaysToKeepStr: '-1', numToKeepStr: '10', artifactNumToKeepStr: '10'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    parameters {
        string(
            defaultValue: '1.9.1',
            description: 'Set tag',
            name: 'VERSION')
    }
    stages {
        stage('Set Tags') {
            steps {
                withCredentials([string(credentialsId: 'GITHUB_SLAVA_T', variable: 'GITHUB_API_TOKEN')]) {
                    sh """
                        echo ${GITHUB_API_TOKEN} > GITHUB_API_TOKEN
                        echo ${VERSION} > VERSION
                    """
                    sh '''
                        mkdir set_tag
                        cd set_tag
                        git clone https://github.com/hors/docker-compose-example ./
                        git checkout 470c98e1bf2f0ef1bbbe306a8602d1f9dcb7f242
                        FULL_SHA='470c98e1bf2f0ef1bbbe306a8602d1f9dcb7f242'

                        set +o xtrace
                        curl -X POST \
                            -H "Authorization: token $(cat ../GITHUB_API_TOKEN)" \
                            -d "{\\"ref\\":\\"refs/tags/v${VERSION}\\",\\"sha\\": \\"${FULL_SHA}\\"}" \
                                https://api.github.com/repos/hors/docker-compose-example/git/refs
                        set -o xtrace
                        cd ../
                        rm -rf ./set_tag
                    '''
                }
            }
        }
    }
    post {
        always {
            deleteDir()
        }
    }
}
