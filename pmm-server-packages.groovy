def app         = 'pmm-server'

def product     = 'pmm-server'
def arch        = 'x86_64'
def os          = 'redhat'
def osVersion   = '7'

echo """
    DESTINATION: ${DESTINATION}
    GIT_BRANCH:  ${GIT_BRANCH}
    VERSION:     ${VERSION}
"""

node('centos7-64') {
    timestamps {
        stage("Fetch spec files") {
            slackSend channel: '@mykola', message: "[${DESTINATION}] ${app} rpm: build started ${env.BUILD_URL}"
            deleteDir()
            git branch: GIT_BRANCH, url: 'https://github.com/Percona-Lab/pmm-server-packaging.git'
            gitCommit = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
            shortCommit = gitCommit.take(6)
        }

        stage("Fetch sources") {
            sh """
                rm -rf  rhel/SPECS/percona-dashboards.spec \
                        rhel/SPECS/pmm-server.spec \
                        rhel/SPECS/pmm-manage.spec \
                        rhel/SPECS/pmm-update.spec \
                        rhel/SPECS/percona-qan-api.spec \
                        rhel/SPECS/percona-qan-app.spec
                ls rhel/SPECS/*.spec | xargs -n 1 spectool -g -C rhel/SOURCES
            """
        }

        stage("Build SRPMs") {
            sh """
                sed -i -e 's/.\\/run.bash/#.\\/run.bash/' rhel/SPECS/golang.spec
                rpmbuild --define "_topdir rhel" -bs rhel/SPECS/*.spec
            """
        }

        stage("Build Golang") {
            sh 'mockchain -c -r epel-7-x86_64 -l result-repo rhel/SRPMS/golang-1.7.3-*.src.rpm'
        }

        stage("Build RPMs") {
            sh 'mockchain -c -r epel-7-x86_64 -l result-repo rhel/SRPMS/*.src.rpm'
            stash includes: 'result-repo/results/epel-7-x86_64/*/*.rpm', name: 'rpms'
            slackSend channel: '@mykola', message: "${app} rpm: build finished"
        }
    }
}

node {
    stage("Upload to repo.ci.percona.com") {
        deleteDir()
        unstash 'rpms'
        def path_to_build = "${DESTINATION}/BUILDS/${product}/${product}-${VERSION}/${GIT_BRANCH}/${shortCommit}/${env.BUILD_NUMBER}"
        sh """
            ssh -i ~/.ssh/percona-jenkins-slave-access uploader@repo.ci.percona.com \
                mkdir -p UPLOAD/${path_to_build}/source/${os} \
                         UPLOAD/${path_to_build}/binary/${os}/${osVersion}/${arch}

            scp -i ~/.ssh/percona-jenkins-slave-access \
                `find result-repo -name '*.src.rpm'` \
                uploader@repo.ci.percona.com:UPLOAD/${path_to_build}/source/${os}/

            scp -i ~/.ssh/percona-jenkins-slave-access \
                `find result-repo -name '*.noarch.rpm' -o -name '*.x86_64.rpm'` \
                uploader@repo.ci.percona.com:UPLOAD/${path_to_build}/binary/${os}/${osVersion}/${arch}/
        """
    }

    stage('Sign RPMs') {
        def path_to_build = "${DESTINATION}/BUILDS/${product}/${product}-${VERSION}/${GIT_BRANCH}/${shortCommit}/${env.BUILD_NUMBER}"
        withCredentials([string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD')]) {
            sh """
                ssh -i ~/.ssh/percona-jenkins-slave-access uploader@repo.ci.percona.com " \
                    /bin/bash -xc ' \
                        ls UPLOAD/${path_to_build}/binary/${os}/${osVersion}/${arch}/*.rpm \
                            | xargs -n 1 signpackage --verbose --password ${SIGN_PASSWORD} --rpm \
                    '"
            """
        }
    }
}

stage('Push to RPM repository') {
    def path_to_build = "${DESTINATION}/BUILDS/${product}/${product}-${VERSION}/${GIT_BRANCH}/${shortCommit}/${env.BUILD_NUMBER}"
    build job: 'push-to-rpm-repository', parameters: [string(name: 'PATH_TO_BUILD', value: "${path_to_build}"), string(name: 'DESTINATION', value: "${DESTINATION}")]
    build job: 'sync-repos-to-production', parameters: [booleanParam(name: 'REVERSE', value: false)]
}