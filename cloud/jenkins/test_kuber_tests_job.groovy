pipeline {
    parameters {
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for percona/percona-xtradb-cluster-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-xtradb-cluster-operator',
            description: 'percona-xtradb-cluster-operator repository',
            name: 'GIT_REPO')
    }
    agent {
         label 'docker' 
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    stages {
        stage('Prepare') {
            steps {
                echo 'Done some work'
            }
        }
        
        stage('Build images') {
            steps {
                echo 'Done' 
            }
        }

        stage('Run firs section of  tests') {
            parallel {
                stage('First tets') {
                       sh '''export GGG=123'''
                       steps {
                           retry(3) {
                                echo 'Done Firs test'
                                sh '''
                                   echo $GG 
                                   sleep 10
                                '''
                           }
                       }
                }
                stage('Second tets') {
                    steps {echo 'Done Second test'}
                }
                stage('Third tets') {
                    steps {
                        sh '''echo $GG'''
                        echo 'Done Thirs test'
                    }
                }
            }
        }
        stage('Run Second section of  tests') {
            parallel {
                stage('First tets') {
                    steps {echo 'Done Firs test'}
                }
                stage('Second tets') {
                    steps {echo 'Done Second test'}
                }
            }
        }
        stage('Run Third section of  tests') {
            parallel {
                stage('First tets') {
                    steps {echo 'Done Firs test'}
                }
                stage('Second tets') {
                    steps {echo 'Done Second test'}
                }
                stage('Third tets') {
                    steps {echo 'Done Thirs test'}
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
