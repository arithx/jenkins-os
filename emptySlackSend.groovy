#!groovy

stage('Downstream') {
    trySlackSend color: 'good',
              message: "Testing slackSend with channel: ''",
              channel: '@slowrie'
}
