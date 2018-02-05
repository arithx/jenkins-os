#!groovy

node('amd64 && kvm && sudo') {
    stage('Build') {
        sh '''#!/bin/bash -ex
exit'''
    }
}

slackSend color: 'bad',
          message: "```Slack Test\n<$BUILD_URL|Link to Build> - <${BUILD_URL}artifacts/_kola_temp.xz|kola_temp.xz>```"
