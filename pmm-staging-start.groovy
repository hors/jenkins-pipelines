pipeline {
    environment {
        specName = 'pmm-staging'
    }
    agent {
        label 'virtualbox'
    }
    parameters {
        string(
            defaultValue: 'perconalab/pmm-server:dev-latest',
            description: '',
            name: 'DOCKER_VERSION')
        string(
            defaultValue: 'latest',
            description: '',
            name: 'CLIENT_VERSION')
        string(
            defaultValue: '',
            description: 'personal public ssh key, ssh user - vagrant',
            name: 'SSH_KEY')
        string(
            defaultValue: '5.7.17-rel11-27.20.2',
            description: 'Percona XtraDB Cluster',
            name: 'PXC_VERSION')
        string(
            defaultValue: '5.7.17-11',
            description: 'Percona Server for MySQL',
            name: 'PS_VERSION')
        string(
            defaultValue: '5.7.17',
            description: 'MySQL Community Server',
            name: 'MS_VERSION')
        string(
            defaultValue: '10.1.22',
            description: 'MariaDB Server',
            name: 'MD_VERSION')
        string(
            defaultValue: '3.4.2-1.2',
            description: 'Percona Server for MongoDB',
            name: 'MO_VERSION')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    triggers {
        upstream upstreamProjects: 'pmm-docker'
    }

    stages {
        stage('Prepare') {
            steps {

                echo """
                    DOCKER_VERSION: ${DOCKER_VERSION}
                    CLIENT_VERSION: ${CLIENT_VERSION}
                    PXC_VERSION:    ${PXC_VERSION}
                    PS_VERSION:     ${PS_VERSION}
                    MS_VERSION:     ${MS_VERSION}
                    MD_VERSION:     ${MD_VERSION}
                    MO_VERSION:     ${MO_VERSION}
                """

                slackSend channel: '#pmm-jenkins', color: '#FFFF00', message: "[${specName}]: build started - ${env.BUILD_URL}"

                sh """
                    export VM_NAME="${specName}-\$(date -u '+%Y%m%d%H%M')"
                    echo \$VM_NAME > VM_NAME

                    export OWNER=\$(
                        curl -s $BUILD_URL/api/json \
                            | python -c "import sys, json; print json.load(sys.stdin)['actions'][1]['causes'][0]['userId']"
                    )
                    echo \$OWNER > OWNER

                    if [ "X$CLIENT_VERSION" = "Xlatest" ]; then
                        CLIENT_VERSION=\$(
                            curl https://www.percona.com/downloads/pmm-client/ \
                                | egrep -o 'pmm-client-[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}' \
                                | sed -e 's/pmm-client-//' \
                                | sort -u -V \
                                | tail -1
                        )
                        echo \$CLIENT_VERSION > CLIENT_VERSION
                    elif [ "X$CLIENT_VERSION" = "Xdev-latest" -o -z "$CLIENT_VERSION" ]; then
                        echo CLIENT_VERSION=dev-latest IS NOT SUPPORTED YET
                        exit 1
                    else
                        echo $CLIENT_VERSION > CLIENT_VERSION
                    fi
                """
                archiveArtifacts 'VM_NAME'
            }
        }

        stage('Run VM') {
            steps {
                sh '''
                    export VM_NAME=\$(cat VM_NAME)
                    export OWNER=\$(cat OWNER)

                    VBoxManage import --vsys 0 --vmname \$VM_NAME \$(ls /mnt/images/Docker-Server-*.ovf | sort  | tail -1)
                    VBoxManage modifyvm \$VM_NAME --nic1 bridged --bridgeadapter1 bond0
                    VBoxManage modifyvm \$VM_NAME --uart1 0x3F8 4 --uartmode1 file /tmp/\$VM_NAME-console.log
                    VBoxManage modifyvm \$VM_NAME --groups "/\$OWNER,/${specName}"
                    VBoxManage startvm --type headless \$VM_NAME

                    for I in $(seq 1 6); do
                        IP=\$(grep eth0: /tmp/\$VM_NAME-console.log | cut -d '|' -f 4 | sed -e 's/ //g')
                        if [ -n "\$IP" ]; then
                            break
                        fi
                        sleep 10
                    done
                    echo \$IP > IP

                    if [ "X\$IP" = "X." ]; then
                        echo Error during DHCP configure. exiting
                        exit 1
                    fi
                '''

                // push ssh key
                sh """
                    if [ -n "$SSH_KEY" ]; then
                        echo '$SSH_KEY' | ssh -o StrictHostKeyChecking=no -i /mnt/images/id_rsa_vagrant vagrant@\$(cat IP) 'cat - >> .ssh/authorized_keys'
                    fi
                """

                // Reconfigure DHCP
                sh '''
                    export IP=\$(cat IP)
                    ssh -o StrictHostKeyChecking=no -i /mnt/images/id_rsa_vagrant vagrant@\$IP '
                        export PATH=$PATH:/usr/sbin
                        ROUTE_DEV=$(ip route get 8.8.8.8 | grep "dev ")
                        GATEWAY=$(echo $ROUTE_DEV | awk "{print\\\$3}")
                        INTERFACE=$(echo $ROUTE_DEV | awk "{print\\\$5}")
                        IP_NETMASK=$(ip address show dev $INTERFACE | grep "inet " | awk "{print\\\$2}")
                        IP=$(echo $IP_NETMASK | cut -d '/' -f 1)
                        NETMASK=$(echo $IP_NETMASK | cut -d '/' -f 2)
                        DNS=$(grep nameserver /etc/resolv.conf | awk "{print\\\$2}" | head -1)
                        echo "
                            DEVICE=$INTERFACE
                            BOOTPROTO=static
                            IPADDR=$IP
                            PREFIX=$NETMASK
                            GATEWAY=$GATEWAY
                            DNS1=$DNS
                            ONBOOT=yes
                            TYPE=Ethernet
                        " | sudo tee /etc/sysconfig/network-scripts/ifcfg-$INTERFACE
                        sudo systemctl restart network docker
                    '
                '''
                archiveArtifacts 'IP'
            }
        }

        stage('Run Docker') {
            steps {
                sh """
                    export IP=\$(cat IP)

                    ssh -o StrictHostKeyChecking=no -i /mnt/images/id_rsa_vagrant vagrant@\$IP "
                        set -o xtrace

                        docker create \
                            -v /opt/prometheus/data \
                            -v /opt/consul-data \
                            -v /var/lib/mysql \
                            -v /var/lib/grafana \
                            --name \$(cat VM_NAME)-data \
                            ${DOCKER_VERSION} /bin/true

                        docker run -d \
                            -p 80:80 \
                            -p 443:443 \
                            --volumes-from \$(cat VM_NAME)-data \
                            --name \$(cat VM_NAME)-server \
                            --restart always \
                            ${DOCKER_VERSION}

                        # it is needed to wait 20 second, it is better to download files instead of sleep command
                        wget --progress=dot:giga \
                            "https://www.percona.com/downloads/pmm-client/pmm-client-\$(cat CLIENT_VERSION)/binary/tarball/pmm-client-\$(cat CLIENT_VERSION).tar.gz" \
                            "https://www.percona.com/downloads/Percona-Server-LATEST/Percona-Server-${PS_VERSION}/binary/tarball/Percona-Server-${PS_VERSION}-Linux.x86_64.ssl101.tar.gz" \
                            "http://nyc2.mirrors.digitalocean.com/mariadb//mariadb-${MD_VERSION}/bintar-linux-x86_64/mariadb-${MD_VERSION}-linux-x86_64.tar.gz" \
                            "https://dev.mysql.com/get/Downloads/MySQL-5.7/mysql-${MS_VERSION}-linux-glibc2.5-x86_64.tar.gz" \
                            "https://www.percona.com/downloads/Percona-XtraDB-Cluster-LATEST/Percona-XtraDB-Cluster-\$(echo "$PXC_VERSION" | sed -r 's/-rel[0-9]{1,2}-/-/; s/[.][0-9]\$//')/binary/tarball/Percona-XtraDB-Cluster-${PXC_VERSION}.Linux.x86_64.ssl101.tar.gz" \
                            "https://www.percona.com/downloads/percona-server-mongodb-LATEST/percona-server-mongodb-${MO_VERSION}/binary/tarball/percona-server-mongodb-${MO_VERSION}-centos7-x86_64.tar.gz"

                        docker logs \$(cat VM_NAME)-server

                        pushd /srv/percona-qa
                            sudo git pull
                        popd

                        tar -zxpf pmm-client-\$(cat CLIENT_VERSION).tar.gz
                        pushd pmm-client-\$(cat CLIENT_VERSION)
                            sudo ./install
                        popd

                        export PATH=\$PATH:/usr/sbin
                        sudo pmm-admin config --client-name pmm-client-hostname --server \$IP
                    "
                """
            }
        }

        stage('Run Clients') {
            steps {
                sh """
                    export IP=\$(cat IP)
                    ssh -o StrictHostKeyChecking=no -i /mnt/images/id_rsa_vagrant vagrant@\$IP "
                        export PATH=\$PATH:/usr/sbin
                        bash /srv/percona-qa/pmm-tests/pmm-framework.sh \
                            --addclient=ps,2  \
                            --addclient=ms,2 \
                            --addclient=md,2 \
                            --addclient=mo,2 \
                            --addclient=pxc,3
                    "
                """
            }
        }
    }

    post {
        always {
            sh '''
                export IP=\$(cat IP)
                export VM_NAME=\$(cat VM_NAME)
                if [ -n "$VM_NAME" ] && [ "X\$IP" = "X." -o -z "\$IP" ]; then
                    VBoxManage controlvm $VM_NAME poweroff
                    VBoxManage unregistervm --delete $VM_NAME
                fi
            '''
        }
        success {
            script {
                def IMAGE = sh(returnStdout: true, script: "cat IP").trim()
                def OWNER = sh(returnStdout: true, script: "cat OWNER").trim()
                slackSend channel: '#pmm-jenkins', color: '#00FF00', message: "[${specName}]: build finished - ${IMAGE}"
                slackSend channel: "@${OWNER}", color: '#00FF00', message: "[${specName}]: build finished - ${IMAGE}"
            }
        }
        failure {
            sh '''
                export VM_NAME=\$(cat VM_NAME)
                if [ -n "$VM_NAME" ]; then
                    VBoxManage controlvm $VM_NAME poweroff
                    VBoxManage unregistervm --delete $VM_NAME
                fi
            '''
            slackSend channel: '#pmm-jenkins', color: '#FF0000', message: "[${specName}]: build failed"
        }
    }
}