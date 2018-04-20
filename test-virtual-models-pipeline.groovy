/**
*
* Test specified virtual model changes.
*
* Expected parameters:
*    CREDENTIALS_ID               ID of jenkins credentials to be used when connecting to gerrit.

*    STACK_RECLASS_ADDRESS        Git URL to reclass model to use for deployment.
*    STACK_RECLASS_BRANCH         Git branch or ref of cluster model to test.
*    STACK_DELETE                 True when delete a stack after failure.
*
*    GERRIT_*                     Gerrit trigger plugin variables.
*    STACK_CLUSTER_NAMES           Comma separated list of cluster names to test. If set and pipeline is
*                                 triggered via gerrit than we will find changed cluster model and run
*                                 tests only when they are present in STACK_CLUSTER_NAMES.
*                                 If pipeline triggered by timer, we will run all clusters specified in
*                                 this variable.
*
*    SYSTEST_JOB                  The name of systest job to run.
*
*    There are 2 options to run the pipeline:
*    1. Manually.
*       In this case need to define SOURCES parameter. See above.
*    2. Automatically by Gerrit trigger.
*       In this case Gerrit trigger adds GERRIT_* paramters to the build and the pipeline will use it.
*       SOURCES parameter should be empty.
*
*/

def common = new com.mirantis.mk.Common()
def aptly = new com.mirantis.mk.Aptly()
def http = new com.mirantis.mk.Http()
def gerrit = new com.mirantis.mk.Gerrit()

def checkouted = false

def testClusterNames
def messages = ["${env.BUILD_URL}"]

def useGerrit = false
def gerritRef
try {
  gerritRef = GERRIT_REFSPEC
  useGerrit = true
} catch (MissingPropertyException e) {
  gerritRef = STACK_RECLASS_BRANCH
}

def gerritUrl
def gitUrl
try {
  gerritUrl = "${GERRIT_SCHEME}://${GERRIT_NAME}@${GERRIT_HOST}:${GERRIT_PORT}/${GERRIT_PROJECT}"
  gerritUrlAnonymous = "https://${GERRIT_HOST}/${GERRIT_PROJECT}"
} catch (MissingPropertyException e) {
  gerritUrl = STACK_RECLASS_ADDRESS
  gerritUrlAnonymous = STACK_RECLASS_ADDRESS
}

def gerritBranch
try {
  gerritBranch = GERRIT_BRANCH
} catch (MissingPropertyException e) {
  gerritBranch = STACK_RECLASS_BRANCH
}

def systestJob = 'oscore-test_virtual_model'
if (common.validInputParam('SYSTEST_JOB')) {
    systestJob = SYSTEST_JOB
}

def setGerritBuildString(buildObj, cluster_name){
    return "* ${cluster_name} ${buildObj.absoluteUrl} : ${buildObj.result} ${buildObj.durationString}"
}

node("oscore-testing") {
  stage("checkout") {
    if(gerritRef != '' && gerritUrl != '') {
        checkouted = gerrit.gerritPatchsetCheckout(gerritUrl, gerritRef, "HEAD", CREDENTIALS_ID)
    } else {
      throw new Exception("Cannot checkout gerrit: ${gerritUrl} branch: ${gerritRef} failed")
    }
  }

  if(common.validInputParam('STACK_CLUSTER_NAMES')){
    if (useGerrit){
      common.infoMsg("STACK_CLUSTER_NAMES was set and pipeline was triggered from gerrit")
      common.infoMsg("Checking if we have to run any clusters in ${STACK_CLUSTER_NAMES}")
      // TODO: add logic to automatically pick changed mode
    } else {
      common.infoMsg("Running deploy for ${STACK_CLUSTER_NAMES}")
      testClusterNames = STACK_CLUSTER_NAMES.tokenize(',')
    }
  }

  def testBuilds = [:]
  stage('Deploy clusters') {
    def deploy_release = [:]
    for (cluster_name in testClusterNames) {
      def cn = cluster_name
      deploy_release["Deploy ${cn}"] = {
        node('oscore-testing') {
          testBuilds["${cn}"] = build job: systestJob, propagate: false, parameters: [
            [$class: 'StringParameterValue', name: 'STACK_RECLASS_ADDRESS', value: gerritUrlAnonymous],
            [$class: 'StringParameterValue', name: 'STACK_RECLASS_BRANCH', value: gerritRef],
            [$class: 'StringParameterValue', name: 'STACK_CLUSTER_NAME', value: cn],
            [$class: 'StringParameterValue', name: 'FORMULA_PKG_REVISION', value: "nightly"],
            [$class: 'BooleanParameterValue', name: 'RUN_SMOKE', value: false],
            [$class: 'BooleanParameterValue', name: 'STACK_DELETE', value: STACK_DELETE.toBoolean()],
            ]
          }
        }
      }
      parallel deploy_release
    }

    def success_models = []
    def failed_models = []
    stage('Managing deployment results') {
      testBuilds.each {
        if (it.value.result != 'SUCCESS') {
          failed_models.add("${it.key}: ${it.value.result}")
        } else {
          success_models.add("${it.key}: ${it.value.result}")
        }
        if (useGerrit){
          messages.add(0, setGerritBuildString(it.value, it.key))
          setGerritReview customUrl: messages.join('\n')
        }
      }
    }
  if(success_models){
    common.successMsg(success_models.join('\n'))
  }
  if (failed_models) {
    common.errorMsg(failed_models.join('\n'))
    error('Some of deploy jobs failed')
  }
}
