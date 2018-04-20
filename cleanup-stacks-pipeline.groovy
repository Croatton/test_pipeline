/**
 *
 * Delete stacks or notify users by Stack name after retention period
 *
 * Expected parameters:
 *   OPENSTACK_API_URL             OpenStack API address
 *   OPENSTACK_API_CREDENTIALS     Credentials to the OpenStack API
 *   OPENSTACK_API_PROJECT         OpenStack project to connect to
 *   OPENSTACK_API_CLIENT          Versions of OpenStack python clients
 *   OPENSTACK_API_VERSION         Version of the OpenStack API (2/3)
 *   OPENSTACK_API_PROJECT_DOMAIN  OpenStack project domain
 *   OPENSTACK_API_PROJECT_ID      OpenStack project do id
 *   OPENSTACK_API_USER_DOMAIN     OpenStack user domain
 *   RETENTION_DAYS                Days to delete stacks after creation
 *   DRY_RUN                       Do not perform actual cleanup
 *   SEND_NOTIFICATIONS            True = Send notifications and DO NOT DELETE outdated stacks. False = Do not send notifications and DELETE outdated stacks
 *   STACK_NAME_PATTERNS_LIST      Comma separated patterns list of stacks names to be inspected.
 *   SLACK_API_URL                 Slack API webhook URL to send notifications.
 *
 *
 */


openstack = new com.mirantis.mk.Openstack()
common = new com.mirantis.mk.Common()
import java.text.SimpleDateFormat

def horizonStackDetailsURL = 'https://cloud-cz.bud.mirantis.net/project/stacks/stack/'

def sendSlackMessage(slackUrl, stackOwner, stacksInfo) {
    def msg = '{"text": "Hi *' + stackOwner + '*! Please consider to delete the following ' + OPENSTACK_API_PROJECT + ' old (created more than ' + RETENTION_DAYS + ' days ago) stacks:", ' +
              '"attachments": [ ' + stacksInfo + ']}'
    common.infoMsg(msg)
    sh 'curl -X POST -H \'Content-type: application/json\' --data \'' + msg + '\' ' + slackUrl
}

node ('python') {
    try {
        // TODO: (oiurchenko) Need to find a way to determine periodic trigger to define correct user.
        def BUILD_USER_ID = 'jenkins'
        wrap([$class: 'BuildUser']) {
            if (env.BUILD_USER_ID) {
                BUILD_USER_ID = env.BUILD_USER_ID
            }
        }

        def outdatedStacks = [:]
        stage('Looking for stacks to be deleted') {
            venv = "${env.WORKSPACE}/venv"
            openstack.setupOpenstackVirtualenv(venv, OPENSTACK_API_CLIENT)
            openstackCloud = openstack.createOpenstackEnv(
                venv, OPENSTACK_API_URL, OPENSTACK_API_CREDENTIALS,
                OPENSTACK_API_PROJECT, OPENSTACK_API_PROJECT_DOMAIN,
                OPENSTACK_API_PROJECT_ID, OPENSTACK_API_USER_DOMAIN,
                OPENSTACK_API_VERSION)
            openstack.getKeystoneToken(openstackCloud, venv)
            def namePatterns = STACK_NAME_PATTERNS_LIST.tokenize(',')
            def candidateStacksToDelete = []
            def deletedStacks = ''
            // Get list of stacks
            for (namePattern in namePatterns){
                candidateStacksToDelete.addAll(openstack.getStacksForNameContains(openstackCloud, namePattern, venv))
            }
            common.infoMsg('Found ' + candidateStacksToDelete.size() + ' stacks')
            // Check each stack
            def toSeconds = 1000
            def currentTimestamp = (long) new Date().getTime() / toSeconds
            def stackInfo = null
            for (stackName in candidateStacksToDelete){
                try{
                    stackInfo = openstack.getHeatStackInfo(openstackCloud, stackName, venv)
                } catch (Exception e) {
                    common.errorMsg('Cannot get stack info for ' + stackName + ' stack with error: ' + e.getMessage())
                    continue
                }
                common.infoMsg('Stack: ' + stackName + ' Creation time: ' + stackInfo.creation_time)
                def creationDate = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH).parse(stackInfo.creation_time.trim())
                def creationTimestamp = (long) creationDate.getTime() / toSeconds
                def diff = currentTimestamp - creationTimestamp
                def retentionSec = Integer.parseInt(RETENTION_DAYS) * 86400
                if (diff > retentionSec){
                    def stackOwner = stackName.split('-')[0]
                    if (SEND_NOTIFICATIONS.toBoolean()){
                        def stackLink = horizonStackDetailsURL + stackInfo.id
                        def stackDetails = '{"title":"' + stackName + '", "title_link": "' + stackLink + '", "footer": "Created at: ' + stackInfo.creation_time.replace('Z', '').replace('T', ' ') + '"}'
                        if (outdatedStacks.containsKey(stackOwner)){
                            outdatedStacks.put(stackOwner, outdatedStacks.get(stackOwner) + ',' + stackDetails)
                        } else {
                            outdatedStacks.put(stackOwner, stackDetails)
                        }
                    }else{
                        if (BUILD_USER_ID == 'jenkins' || BUILD_USER_ID == stackOwner){
                            common.infoMsg(stackName + ' stack have to be deleted')
                            deletedStacks = deletedStacks + 'Stack: ' + stackName + ' Creation time: ' + stackInfo.creation_time + '\n'
                            if (DRY_RUN.toBoolean()){
                                common.infoMsg('Dry run mode. No real deleting')
                            }
                            else{
                                try{
                                    openstack.deleteHeatStack(openstackCloud, stackName, venv)
                                } catch (Exception e) {
                                    common.errorMsg('Cannot delete stack ' + stackName + ' with error: ' + e.getMessage())
                                }
                            }
                        }else{
                            common.infoMsg('Only jenkins user or stack owner can delete stack. Do not delete ' + stackName + ' stack')
                        }
                    }
                }
            }
            common.infoMsg('The following stacks were deleted: \n' + deletedStacks)
        }
        stage('Sending messages') {
            if (SEND_NOTIFICATIONS.toBoolean()){
                for (Map.Entry<String, String> entry : outdatedStacks.entrySet()) {
                    sendSlackMessage(SLACK_API_URL, entry.getKey(), entry.getValue())
                }
            }
        }
    } catch (Exception e) {
        currentBuild.result = 'FAILURE'
        throw e
    }
}
