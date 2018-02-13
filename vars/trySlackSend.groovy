/*
 * Sends a notification to Slack.  If the Slack Notification Plugin is not
 * configured the the notification will be echoed to the Jenkins build log.
 */

def call(String channel = '', String color = 'good', String message) {
    try {
        slackSend(channel: channel, color: color, message: message)
    } catch (NoSuchMethodError err) {
        echo 'Slack Notification: ' + message
    }
}
