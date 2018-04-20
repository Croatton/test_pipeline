/**
 *
 * Delete devops environments which are older than RETENTION_DAYS and contains a pattern from STACK_NAME_PATTERNS_LIST
 *
 * Expected parameters:
 *   RETENTION_DAYS                Days to delete stacks after creation
 *   DRY_RUN                       Do not perform actual cleanup
 *   ENV_NAME_PATTERNS_LIST        Patterns to search in env names
 *   SLAVES                        Space separated list of slaves which should be cleaned
 *   SLAVES_LABEL                  Label for finding slaves to run cleanup on
 *
 */
common = new com.mirantis.mk.Common()
python = new com.mirantis.mk.Python()

/**
 * Returns old stacks list
 *
 * @param path Path to dos.py
 * @param envVars variables to pass to env
 * @param pattern to find stacks
 * @param days to filter stacks
*/
def getKvmOldStacks(venv, envVars, pattern, days){
    withEnv(envVars) {
        return python.runVirtualenvCommand(venv, "dos.py list-old ${days}d | grep ${pattern} || true").tokenize('\n')
    }
}

/**
 * Erases the env
 *
 * @param path Path to dos.py
 * @param env name of the ENV have to be deleted
*/
def eraseDevOpsEnv(venv, env, envVars){
    echo "${env} will be erased"
    withEnv(envVars) {
        return python.runVirtualenvCommand(venv, "dos.py erase ${env}")
    }
}

def getSlaves(label) {
    slaves = []
    for (slave in jenkins.model.Jenkins.instance.slaves) {
        if (slave.getLabelString().contains(label)) {
            slaves.add(slave.nodeName)
        }
    }
    return slaves
}

def slaves = []
def namePatterns = ENV_NAME_PATTERNS_LIST.tokenize(',')
def devops_work_dir = '/var/fuel-devops-venv'
def envVars = ["WORKING_DIR=${devops_work_dir}",
               "DEVOPS_DB_NAME=${devops_work_dir}/fuel-devops.sqlite",
               'DEVOPS_DB_ENGINE=django.db.backends.sqlite3',]

if (common.validInputParam('SLAVES')) {
    slaves = SLAVES.tokenize()
} else if (common.validInputParam('SLAVES_LABEL')){
    slaves = getSlaves(SLAVES_LABEL)
} else {
    error('SLAVES or SLAVES_LABEL parameters are not set')
}

for (slave in slaves) {

    node(slave) {
        def venv = "${env.WORKSPACE}/devops-venv"
        def envsToRemove = []
        def erased_envs = []
        stage('setting up devops env') {
            python.setupDevOpsVenv(venv)
        }

        stage('Looking for envs to be deleted') {
            // Get list of stacks
            for (namePattern in namePatterns){
                envsToRemove.addAll(getKvmOldStacks(venv, envVars, namePattern, RETENTION_DAYS))
            }
            common.infoMsg('Found ' + envsToRemove.size() + ' stacks')
            if (DRY_RUN.toBoolean()){
                print envsToRemove
                common.infoMsg('Dry run mode. No real deleting. The following envs could be deleted: \n' + envsToRemove)
            } else if (envsToRemove){
                for (env in envsToRemove) {
                    common.infoMsg('Removing devops env:' + env)
                    try {
                        eraseDevOpsEnv(venv, env, envVars)
                        erased_envs.add(env)
                    } catch (Exception e) {
                        currentBuild.result = 'FAILURE'
                        common.infoMsg("Failed to remove env ${env} on ${slave}")
                    }
                }
            } else {
                common.infoMsg("No envs to remove are found on ${slave}")
            }
        }
        if (erased_envs) {
            common.infoMsg("The following envs were deleted on ${slave}: \n" + erased_envs)
        }
    }
}
