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
// Publish RPMs to repo.ci.percona.com
        stage('Copy RPMs to PMM repo') {
            steps {
                unstash 'copy'
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh '''
                        cat copy.list | ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                            "cat - | xargs -I{} cp -v /srv/repo-copy/pmm2-components/yum/${UPDATER_REPO}/7/RPMS/x86_64/{} /srv/repo-copy/pmm2-components/yum/release/7/RPMS/x86_64/{}"
                    '''
                }
            }
        }
        stage('Createrepo') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh '''
                        ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                            createrepo --update /srv/repo-copy/pmm2-components/yum/release/7/RPMS/x86_64/
                    '''
                }
            }
        }
// Publish RPMs to repo.percona.com
        stage('Publish RPMs') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh """
                        ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com "
                            rsync -avt --bwlimit=50000 --delete --progress --exclude=rsync-* --exclude=*.bak \
                                /srv/repo-copy/pmm2-components/yum/release \
                                10.10.9.209:/www/repo.percona.com/htdocs/pmm2-components/yum/
                        "
                    """
                }
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
            }
        }
        stage('Set Docker Tag') {
            agent {
                label 'min-centos-7-x64'
            }
            steps {
                installDocker()
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        sg docker -c "
                            docker login -u "${USER}" -p "${PASS}"
                        "
                    """
                }
                sh """
                    sg docker -c "
                        docker pull ${DOCKER_VERSION}
                        docker tag ${DOCKER_VERSION} percona/pmm-server:${VERSION}
                        docker tag ${DOCKER_VERSION} percona/pmm-server:${DOCKER_MID}
                        docker tag ${DOCKER_VERSION} percona/pmm-server:${TOP_VER}
                        docker push percona/pmm-server:${VERSION}
                        docker push percona/pmm-server:${DOCKER_MID}
                        docker push percona/pmm-server:${TOP_VER}
                        if [ ${TOP_VER} = 1 ]; then
                            docker push percona/pmm-server:latest
                        fi
                        docker save percona/pmm-server:${VERSION} | xz > pmm-server-${VERSION}.docker

                        docker pull ${DOCKER_CLIENT_VERSION}
                        docker tag ${DOCKER_CLIENT_VERSION} perconalab/pmm-client:${VERSION}
                        docker tag ${DOCKER_CLIENT_VERSION} perconalab/pmm-client:latest
                        docker push perconalab/pmm-client:${VERSION}
                        docker save perconalab/pmm-client:${VERSION} | xz > pmm-client-${VERSION}.docker
                    "
                """
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        aws s3 cp --only-show-errors pmm-server-${VERSION}.docker s3://percona-vm/pmm-server-${VERSION}.docker
                        aws s3 cp --only-show-errors pmm-client-${VERSION}.docker s3://percona-vm/pmm-client-${VERSION}.docker
                    """
                }
                deleteDir()
            }
        }
    }
    post {
        always {
            deleteDir()
        }
        success {
            unstash 'copy'
            script {
                def IMAGE = sh(returnStdout: true, script: "cat copy.list").trim()
                slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${specName}]: build finished - ${IMAGE}"
            }
        }
        failure {
            slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${specName}]: build failed"
        }
    }
}
