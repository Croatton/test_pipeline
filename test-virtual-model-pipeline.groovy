/**
*
* Test specified virtual model changes.
*
* Expected parameters:
*    CREDENTIALS_ID                    ID of jenkins credentials to be used when connecting to gerrit.
*    STACK_RECLASS_ADDRESS             Git URL to reclass model to use for deployment.
*    STACK_RECLASS_BRANCH              Git branch or ref of cluster model to test.
*    STACK_CLUSTER_NAME                The name of cluster to test.
*
*    FORMULA_PKG_REVISION              Formulas release to deploy with (stable, testing or nightly)
*    BOOTSTRAP_EXTRA_REPO_PARAMS       List of extra repos and related parameters injected on salt bootstrap stage:
*                                      repo 1, repo priority 1, repo pin 1; repo 2, repo priority 2, repo pin 2
*
*    DEPLOY_JOB_NAME                   The name of job to deploy cluster with. By default constructed with
*                                      "deploy-heat-$(STACK_CLUSTER_NAME.replace('-', '_'))"
*    STACK_TEST_JOB                    The name of job to perform test on the environment.
*    STACK_DELETE                      Whether to cleanup created stack
*
*    RUN_SMOKE                         Bool value to specify if run smoke after deployment or not.
*
*    OPENSTACK_VERSION                 Version of Openstack being tested
*    OPENSTACK_API_URL                 OpenStack API address
*    OPENSTACK_API_CREDENTIALS         Credentials to the OpenStack API
*    OPENSTACK_API_PROJECT             OpenStack project to connect to
*    OPENSTACK_API_PROJECT_DOMAIN      OpenStack project domain to connect to
*    OPENSTACK_API_PROJECT_ID          OpenStack project ID to connect to
*    OPENSTACK_API_USER_DOMAIN         OpenStack user domain
*    OPENSTACK_API_CLIENT              Versions of OpenStack python clients
*    OPENSTACK_API_VERSION             Version of the OpenStack API (2/3)
*
*
*/

def common = new com.mirantis.mk.Common()
def aptly = new com.mirantis.mk.Aptly()
def http = new com.mirantis.mk.Http()
def gerrit = new com.mirantis.mk.Gerrit()

def stackName
def saltMasterUrl

def deployJobName
if (common.validInputParam('DEPLOY_JOB_NAME')) {
  deployJobName = DEPLOY_JOB_NAME
} else {
  deployJobName = "deploy-heat-${STACK_CLUSTER_NAME.replace('-', '_')}"
}

def runSmoke = true
if (common.validInputParam('RUN_SMOKE')) {
  runSmoke = RUN_SMOKE.toBoolean()
}

def stackTestJob
if (common.validInputParam('STACK_TEST_JOB')) {
  stackTestJob = STACK_TEST_JOB
} else {
  stackTestJob = "oscore-tempest-runner"
}

def testConcurrency
if (common.validInputParam('TEST_CONCURRENCY')) {
  testConcurrency = TEST_CONCURRENCY
} else {
  testConcurrency = "2"
}

def testTarget
if (common.validInputParam('TEST_TARGET')) {
  testTarget = TEST_TARGET
}

def stackCleanupJob
if (common.validInputParam('STACK_CLEANUP_JOB')) {
  stackCleanupJob = STACK_CLEANUP_JOB
} else {
  stackCleanupJob = 'deploy-stack-cleanup'
}

def extraRepo
if (common.validInputParam('BOOTSTRAP_EXTRA_REPO_PARAMS')) {
  extraRepo = BOOTSTRAP_EXTRA_REPO_PARAMS
} else {
  extraRepo = ''
}

timeout(time: 6, unit: 'HOURS') {
    node("oscore-testing") {

      try {
        stage('Deploy cluster') {
          deployBuild = build( job: "${deployJobName}", propagate: false, parameters: [
            [$class: 'StringParameterValue', name: 'STACK_RECLASS_ADDRESS', value: STACK_RECLASS_ADDRESS],
            [$class: 'StringParameterValue', name: 'STACK_RECLASS_BRANCH', value: STACK_RECLASS_BRANCH],
            [$class: 'StringParameterValue', name: 'FORMULA_PKG_REVISION', value: FORMULA_PKG_REVISION],
            [$class: 'StringParameterValue', name: 'HEAT_STACK_ZONE', value: HEAT_STACK_ZONE],
            [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT', value: OPENSTACK_API_PROJECT],
            [$class: 'StringParameterValue', name: 'BOOTSTRAP_EXTRA_REPO_PARAMS', value: extraRepo],
            [$class: 'BooleanParameterValue', name: 'STACK_DELETE', value: false],
            ]
          )
          // get salt master url
          saltMasterUrl = "http://${deployBuild.description.tokenize(' ')[1]}:6969"
          common.infoMsg("Salt API is accessible via ${saltMasterUrl}")

          // Try to set stack name for stack cleanup job
          if (deployBuild.description) {
            stackName = deployBuild.description.tokenize(' ')[0]
          }
          if (deployBuild.result != 'SUCCESS'){
            error("Deployment failed, please check ${deployBuild.absoluteUrl}")
          }
        }

        // Perform smoke tests to fail early
        stage('Run tests'){
          if (runSmoke){
            common.infoMsg('Running smoke tests')
            build(job: stackTestJob, parameters: [
              [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: saltMasterUrl],
              [$class: 'StringParameterValue', name: 'TEST_TARGET', value: testTarget],
              [$class: 'StringParameterValue', name: 'TEST_PATTERN', value: "smoke"],
              [$class: 'StringParameterValue', name: 'TEST_CONCURRENCY', value: testConcurrency],
              [$class: 'BooleanParameterValue', name: 'TESTRAIL', value: false],
              [$class: 'StringParameterValue', name: 'PROJECT', value: 'smoke'],
              [$class: 'StringParameterValue', name: 'TEST_PASS_THRESHOLD', value: '100'],
              [$class: 'BooleanParameterValue', name: 'FAIL_ON_TESTS', value: true],
            ])
          }
        }

      } catch (Exception e) {
        currentBuild.result = 'FAILURE'
        throw e
      } finally {
        if (common.validInputParam('STACK_DELETE') && STACK_DELETE.toBoolean() == true) {
          try {
            if (!stackName){
              error('Stack cleanup parameters are undefined, cannot cleanup')
            }
            stage('Trigger cleanup job') {
              common.errorMsg('Stack cleanup job triggered')
              build(job: stackCleanupJob, parameters: [
                [$class: 'StringParameterValue', name: 'STACK_NAME', value: stackName],
                [$class: 'StringParameterValue', name: 'STACK_TYPE', value: 'heat'],
                [$class: 'StringParameterValue', name: 'OPENSTACK_API_URL', value: OPENSTACK_API_URL],
                [$class: 'StringParameterValue', name: 'OPENSTACK_API_CREDENTIALS', value: OPENSTACK_API_CREDENTIALS],
                [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT', value: OPENSTACK_API_PROJECT],
                [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT_DOMAIN', value: OPENSTACK_API_PROJECT_DOMAIN],
                [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT_ID', value: OPENSTACK_API_PROJECT_ID],
                [$class: 'StringParameterValue', name: 'OPENSTACK_API_USER_DOMAIN', value: OPENSTACK_API_USER_DOMAIN],
                [$class: 'StringParameterValue', name: 'OPENSTACK_API_CLIENT', value: OPENSTACK_API_CLIENT],
                [$class: 'StringParameterValue', name: 'OPENSTACK_API_VERSION', value: OPENSTACK_API_VERSION],
                ]
              )
            }
          } catch (Exception e) {
            common.errorMsg("Stack cleanup failed\n${e.message}")
          }
        }
      }
    }
}
