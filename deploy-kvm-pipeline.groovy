/**
* STACK_NAME            The prefix for env name is going to be created
* TEMPLATE              There are two templates are available for one-node installation and two-node (Single or Multi)
* DESTROY_ENV           To shutdown env once job is finished
* DEPLOY_OPENSTACK      if set True OpenStack will be deployed
* SLAVE_NODE            The node where VM is going to be created
* JOB_DEP_NAME          Job name which deployes stack
* CREATE_ENV            Enable if the env have to be created
*/

common = new com.mirantis.mk.Common()
git = new com.mirantis.mk.Git()
python = new com.mirantis.mk.Python()

/**
 * Creates env according to input params by DevOps tool
 *
 * @param path Path to dos.py
 * @param work_dir path where devops is installed
 * @param type Path to template having been created
 */
def createDevOpsEnv(path, tpl, envVars){
//    echo "${path} ${tpl}"
    withEnv(envVars) {
        return sh(script: "${path} create-env ${tpl}", returnStdout: true, )
    }
}

/**
 * Erases the env
 *
 * @param path Path to dos.py
 * @param env name of the ENV have to be deleted
  */
def eraseDevOpsEnv(path, env, envVars){
    echo "${env} will be erased"
    withEnv(envVars) {
        return sh(script: "${path} erase ${env}", returnStdout: true, )
    }
}

/**
 * Shutdown the env
 *
 * @param path Path to dos.py
 * @param env name of the ENV have to be destroyed
  */
def destroyDevOpsEnv(path, env, envVars){
    withEnv(envVars) {
        return sh(script: "${path} destroy ${env}", returnStdout: true, )
    }
}

/**
 * Starts the env
 *
 * @param path Path to dos.py
 * @param work_dir path where devops is installed
 * @param env name of the ENV have to be brought up
  */
def startupDevOpsEnv(path, env, envVars){
    withEnv(envVars) {
        return sh(script: "${path} start ${env}", returnStdout: true, )
    }
}

/**
 * Get env IP
 *
 * @param path Path to dos.py
 * @param work_dir path where devops is installed
 * @param env name of the ENV to find out IP
  */
def getDevOpsIP(path, env, envVars){
    withEnv(envVars) {
        return sh(script: "${path} slave-ip-list --address-pool-name public-pool01 --ip-only ${env}", returnStdout: true, ).trim()
    }
}

def ifEnvIsReady(envip){
    def retries = 50
    if (retries != -1){
        retry(retries){
            return sh(script: "nc -z -w 30 ${envip} 22", returnStdout: true, )
        }
        common.successMsg("The env with IP ${envip} has been started")
    } else {
        echo 'It seems the env has not been started properly'
    }
}

def setupDevOpsVenv(venv) {
    requirements = ['git+https://github.com/openstack/fuel-devops.git']
    python.setupVirtualenv(venv, 'python2', requirements)
}

node('oscore-testing') {
    def venv="${env.WORKSPACE}/devops-venv"
    def devops_dos_path = "${venv}/bin/dos.py"
    def devops_work_dir = '/var/fuel-devops-venv'
    def envname

    try {
        setupDevOpsVenv(venv)

        List envVars = ["WORKING_DIR=${devops_work_dir}", "DEVOPS_DB_NAME=${devops_work_dir}/fuel-devops.sqlite", 'DEVOPS_DB_ENGINE=django.db.backends.sqlite3']

        if (CREATE_ENV.toBoolean()) {
          def dt = new Date().getTime()
          envname = "${params.STACK_NAME}-${dt}"
          envVars.push("ENV_NAME=${envname}")

          stage ('Creating environmet') {
              // get DevOps templates
              git.checkoutGitRepository('templates', 'https://gerrit.mcp.mirantis.net/openstack-ci/openstack-pipelines.git', 'master', '')

              if (!common.validInputParam('STACK_NAME')) {
                  error('STACK_NAME variable have to be defined')
              }
              echo "${STACK_NAME} ${TEMPLATE}"
              if (TEMPLATE == 'AIO') {
                  tpl = "${env.WORKSPACE}/templates/devops/clound-init-single.yaml"
              } else if (TEMPLATE == 'Multi') {
                  //multinode deployment will be here
                  echo 'Multi'
              }
              createDevOpsEnv(devops_dos_path, tpl, envVars)
          }
          stage ('Bringing up the environment') {
              startupDevOpsEnv(devops_dos_path, envname, envVars)
          }
          stage ('Getting environment IP') {
              envip = getDevOpsIP(devops_dos_path, envname, envVars)
              currentBuild.description = "${envname} ${envip} ${env.NODE_NAME}"
          }
          stage ('Checking whether the env has finished starting') {
              ifEnvIsReady(envip)

              if (DEPLOY_OPENSTACK.toBoolean()) {
                   stack_deploy_job = "deploy-${STACK_TYPE}-${TEST_MODEL}"
                    stage('Trigger deploy job') {
                        deployBuild = build(job: stack_deploy_job, parameters: [
                            [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT', value: OPENSTACK_API_PROJECT],
                            [$class: 'StringParameterValue', name: 'HEAT_STACK_ZONE', value: HEAT_STACK_ZONE],
                            [$class: 'StringParameterValue', name: 'STACK_INSTALL', value: STACK_INSTALL],
                            [$class: 'StringParameterValue', name: 'STACK_TEST', value: ''],
                            [$class: 'StringParameterValue', name: 'STACK_TYPE', value: STACK_TYPE],
                            [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: "http://${envip}:6969"],
                            [$class: 'StringParameterValue', name: 'SLAVE_NODE', value: "${env.NODE_NAME}"],
                            [$class: 'BooleanParameterValue', name: 'STACK_DELETE', value: false],
                            [$class: 'TextParameterValue', name: 'SALT_OVERRIDES', value: SALT_OVERRIDES]
                        ])
                    }
              }
          }
        } else if (DESTROY_ENV.toBoolean()) {
                  stage ('Bringing down environmnet') {
                      if (STACK_NAME) {
                        envVars.push("ENV_NAME=${STACK_NAME}")
                        destroyDevOpsEnv("${devops_dos_path}", STACK_NAME, envVars)
                      } else {
                        envVars.push("ENV_NAME=${envname}")
                        destroyDevOpsEnv("${devops_dos_path}", "${envname}", envVars)
                      }
                  }
        }
    } catch (Exception e) {
        currentBuild.result = 'FAILURE'
        throw e
    }
}

