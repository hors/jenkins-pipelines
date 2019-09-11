library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    environment {
        specName = 'pmm2-release'
    }
    agent {
        label 'master'
    }
    parameters {
        choice(
            choices: 'laboratory',
            description: 'publish pmm2-server packages from regular(laboratory) repository',
            name: 'UPDATER_REPO')
        string(
            defaultValue: 'perconalab/pmm-server:dev-latest',
            description: 'pmm-server container version (image-name:version-tag)',
            name: 'DOCKER_VERSION')
        string(
            defaultValue: 'perconalab/pmm-client:dev-latest',
            description: 'pmm-client docker container version (image-name:version-tag)',
            name: 'DOCKER_CLIENT_VERSION')
        string(
            defaultValue: '2.0.0',
            description: 'PMM2 Server version',
            name: 'VERSION')
    }
    stages {
        stage('Get Docker RPMs') {
            agent {
                label 'min-centos-7-x64'
            }
            steps {
                installDocker()
                slackSend channel: '#pmm-ci', color: '#FFFF00', message: "[${specName}]: build started - ${BUILD_URL}"
                sh "sg docker -c 'docker run ${DOCKER_VERSION} /usr/bin/rpm -qa' > rpms.list"
                stash includes: 'rpms.list', name: 'rpms'
            }
        }
        stage('Get repo RPMs') {
            steps {
                unstash 'rpms'
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh '''
                        ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                            ls /srv/repo-copy/pmm2-components/yum/${UPDATER_REPO}/7/RPMS/x86_64 \
                            > repo.list
                        cat rpms.list \
                            | grep -v 'pmm2-client' \
                            | sed -e 's/[^A-Za-z0-9\\._+-]//g' \
                            | xargs -n 1 -I {} grep "^{}.rpm" repo.list \
                            | sort \
                            | tee copy.list
                    '''
                }
                stash includes: 'copy.list', name: 'copy'
                archiveArtifacts 'copy.list'
            }
        }
        stage('Set Tags') {
            steps {
                withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
                    unstash 'copy'
                    sh """
                        echo ${GITHUB_API_TOKEN} > GITHUB_API_TOKEN
                        echo ${VERSION} > VERSION
                    """
                    sh '''
                        export VERSION=$(cat VERSION)
                        export TOP_VER=$(cat VERSION | cut -d. -f1)
                        export MID_VER=$(cat VERSION | cut -d. -f2)
                        export DOCKER_MID="$TOP_VER.$MID_VER"
                        declare -A repo=(
                            ["percona-dashboards"]="percona/grafana-dashboards"
                            ["pmm-server"]="percona/pmm-server"
                            ["percona-qan-api2"]="percona/qan-api2"
                            ["pmm-update"]="percona/pmm-update"
                            ["pmm-managed"]="percona/pmm-managed"
                        )
                        for package in "${!repo[@]}"; do
                            SHA=$(
                                grep "^$package-$VERSION-" copy.list \
                                    | perl -p -e 's/.*[.]\\d{10}[.]([0-9a-f]{7})[.]el7.*/$1/'
                            )
                            if [[ -n "$package" ]] && [[ -n "$SHA" ]]; then
                                rm -fr $package
                                mkdir $package
                                pushd $package >/dev/null
                                    git clone https://github.com/${repo["$package"]} ./
                                    git checkout $SHA
                                    FULL_SHA=$(git rev-parse HEAD)

                                    echo "$FULL_SHA"
                                    echo "$VERSION"
                                popd >/dev/null
                            fi
                        done
                    '''
                }
                stash includes: 'VERSION', name: 'version_file'
            }
        }
        stage('Set Docker Tag') {
            agent {
                label 'min-centos-7-x64'
            }
            steps {
                unstash 'version_file'
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        sg docker -c "
                            docker login -u "${USER}" -p "${PASS}"
                        "
                    """
                }
                sh """
                    VERSION=\$(cat VERSION)
                    TOP_VER=\$(cat VERSION | cut -d. -f1)
                    MID_VER=\$(cat VERSION | cut -d. -f2)
                    DOCKER_MID="\$TOP_VER.\$MID_VER"
                    sg docker -c "
                        echo "\${VERSION}"
                        echo "\${MID_VER}"
                        echo "\${TOP_VER}"
                        echo "\${DOCKER_MID}"
                        echo "\${DOCKER_VERSION}"
                    "
                """
                deleteDir()
            }
        }
    }
    post {
        always {
            deleteDir()
        }
    }
}
