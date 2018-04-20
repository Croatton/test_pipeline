/**
 *
 * Wrapper pipeline for automated tests of commits to docker tempest image.
 *
 * Flow parameters:
 *   BOOTSTRAP_EXTRA_REPO_PARAMS       List of extra repos and related parameters injected on salt bootstrap stage:
 *                                     repo 1, repo priority 1, repo pin 1; repo 2, repo priority 2, repo pin 2
 *   FAIL_ON_TESTS                     Whether to fail build on tests failures or not
 *   FORMULA_PKG_REVISION              Formulas release to deploy with (stable, testing or nightly)
 *   GERRIT_PROJECT_URL                Url to project with docker image source
 *   GERRIT_BRANCH                     Branch of project with docker image source
 *   HEAT_STACK_ZONE                   VM availability zone
 *   OPENSTACK_VERSION                 Version of Openstack being tested
 *   OPENSTACK_API_URL                 OpenStack API address
 *   OPENSTACK_API_CREDENTIALS         Credentials to the OpenStack API
 *   OPENSTACK_API_PROJECT             OpenStack project to connect to
 *   OPENSTACK_API_PROJECT_DOMAIN      OpenStack project domain to connect to
 *   OPENSTACK_API_PROJECT_ID          OpenStack project ID to connect to
 *   OPENSTACK_API_USER_DOMAIN         OpenStack user domain
 *   OPENSTACK_API_CLIENT              Versions of OpenStack python clients
 *   OPENSTACK_API_VERSION             Version of the OpenStack API (2/3)
 *   CREDENTIALS_ID                    ID of credentials to connect to gerrit via ssh
 *   SALT_MASTER_IP                    IP of the salt master, if specified env deployment will be skipped
 *   UPLOAD_CREDENTIALS_ID             ID of credentials to connect to target host
 *   STACK_DELETE                      Whether to cleanup created stack
 *   STACK_TEST_JOB                    Job for launching tests
 *   STACK_INSTALL                     Which components of the stack to install
 *   STACK_RECLASS_ADDRESS             Url to repository with stack salt models
 *   STACK_RECLASS_BRANCH              Branch of repository with stack salt models
 *   STACK_TYPE                        Environment type (heat, physical, kvm)
 *   TEST_CONF                         Tempest configuration file path inside container
 *   TEST_TARGET                       Salt target for tempest tests
 *   TEST_MODEL                        Reclass model of environment
 *
 **/
def ssh = new com.mirantis.mk.Ssh()
def gerrit = new com.mirantis.mk.Gerrit()
def git = new com.mirantis.mk.Git()
def common = new com.mirantis.mk.Common()
def build_result = 'FAILURE'

node('oscore-testing') {
    def stack_deploy_job = "deploy-${STACK_TYPE}-${TEST_MODEL}"
    def bootstrap_extra_repo_params = ''
    def deployBuild
    def salt_master_ip
    def salt_master_url
    def stack_name
    def formula_pkg_revision = 'stable'
    // if stack reclass parameters are left empty, than default from heat template will be used
    def stack_reclass_address = ''
    def stack_reclass_branch = ''

    def built_image
    def docker_context = '.'
    int currentTimestamp = (long) new Date().getTime() / 1000
    def project_url = GERRIT_PROJECT_URL ?: "${GERRIT_SCHEME}://${GERRIT_NAME}@${GERRIT_HOST}:${GERRIT_PORT}/${GERRIT_PROJECT}"
    def image_short_name = project_url.tokenize('/').last() - ~/\.git$/
    def image_full_name = "${image_short_name}-${currentTimestamp}"
    def image_file = "${image_short_name}.tar"

    if (common.validInputParam('STACK_RECLASS_ADDRESS')) {
        stack_reclass_address = STACK_RECLASS_ADDRESS
    }
    if (common.validInputParam('STACK_RECLASS_BRANCH')) {
        stack_reclass_branch = STACK_RECLASS_BRANCH
    }

    if (common.validInputParam('BOOTSTRAP_EXTRA_REPO_PARAMS')) {
        bootstrap_extra_repo_params = BOOTSTRAP_EXTRA_REPO_PARAMS
    }

    if (common.validInputParam('FORMULA_PKG_REVISION')) {
        formula_pkg_revision = FORMULA_PKG_REVISION
    }

    try {

        // Set current build description
        if (common.validInputParam('GERRIT_REFSPEC')){
            currentBuild.description = """
              Triggered by change: <a href=${GERRIT_CHANGE_URL}>${GERRIT_CHANGE_NUMBER},${GERRIT_PATCHSET_NUMBER}</a><br/>
              Project: <b>${GERRIT_PROJECT}</b><br/>
              Branch: <b>${GERRIT_BRANCH}</b><br/>
              Subject: <b>${GERRIT_CHANGE_SUBJECT}</b><br/>
            """
        } else {
            currentBuild.description = """
              Triggered manually<br/>
              Git repository URL: <b>${GERRIT_PROJECT_URL}</b><br/>
              Git revision: <b>${GERRIT_BRANCH}</b><br/>
            """
        }

        dir(image_short_name) {
            deleteDir()
            stage('Checkout'){
                if (common.validInputParam('GERRIT_REFSPEC')){
                    gerrit.gerritPatchsetCheckout(project_url, GERRIT_REFSPEC, GERRIT_BRANCH, CREDENTIALS_ID)
                    // repository name must be lowercase
                    image_full_name = "${image_full_name}-cr-${GERRIT_CHANGE_NUMBER}"
                } else {
                    git.checkoutGitRepository('.', project_url, GERRIT_BRANCH, CREDENTIALS_ID)
                }
            }

            // Build image
            stage('Build ' + image_short_name) {
                def docker_args = [
                    '--pull',
                    '--no-cache',
                    docker_context,
                ]
                built_image = docker.build(
                    image_full_name,
                    docker_args.join(' ')
                )

                sh("docker save -o ${image_file} ${image_full_name}")
            }
        }

        if (common.validInputParam('SALT_MASTER_IP')){
            salt_master_ip = SALT_MASTER_IP
        } else {
            // Deploy MCP environment
            stage('Trigger deploy job') {
                deployBuild = build(job: stack_deploy_job, propagate: false, parameters: [
                    [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT', value: OPENSTACK_API_PROJECT],
                    [$class: 'StringParameterValue', name: 'HEAT_STACK_ZONE', value: HEAT_STACK_ZONE],
                    [$class: 'StringParameterValue', name: 'STACK_INSTALL', value: STACK_INSTALL],
                    [$class: 'StringParameterValue', name: 'STACK_RECLASS_ADDRESS', value: stack_reclass_address],
                    [$class: 'StringParameterValue', name: 'STACK_RECLASS_BRANCH', value: stack_reclass_branch],
                    [$class: 'StringParameterValue', name: 'STACK_TEST', value: ''],
                    [$class: 'StringParameterValue', name: 'STACK_TYPE', value: STACK_TYPE],
                    [$class: 'StringParameterValue', name: 'FORMULA_PKG_REVISION', value: formula_pkg_revision],
                    [$class: 'BooleanParameterValue', name: 'STACK_DELETE', value: false],
                    [$class: 'StringParameterValue', name: 'BOOTSTRAP_EXTRA_REPO_PARAMS', value: bootstrap_extra_repo_params],
                ])
            }
            // get salt master url
            salt_master_ip = deployBuild.description.tokenize(' ')[1]

            // Try to set stack name for stack cleanup job
            if (deployBuild.description) {
                stack_name = deployBuild.description.tokenize(' ')[0]
            }
            if (deployBuild.result != 'SUCCESS'){
                error("Deployment failed, please check ${deployBuild.absoluteUrl}")
            }
        }

        salt_master_url = "http://${salt_master_ip}:6969"
        common.infoMsg("Salt API is accessible via ${salt_master_url}")

        stage('Uploading image to environment') {
            ssh.ensureKnownHosts("${salt_master_ip}")
            ssh.prepareSshAgentKey(UPLOAD_CREDENTIALS_ID)
            ssh.runSshAgentCommand("scp ${image_short_name}/${image_file} ubuntu@${salt_master_ip}:/tmp/")
        }

        //Perform smoke tests to fail early
        stage('Run Smoke tests') {
            build(job: STACK_TEST_JOB, parameters: [
                [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: salt_master_url],
                [$class: 'StringParameterValue', name: 'LOCAL_TEMPEST_IMAGE', value: "/tmp/${image_file}"],
                [$class: 'StringParameterValue', name: 'TEST_IMAGE', value: "${image_full_name}"],
                [$class: 'StringParameterValue', name: 'TEST_TARGET', value: TEST_TARGET],
                [$class: 'StringParameterValue', name: 'TEST_SET', value: 'smoke'],
                [$class: 'StringParameterValue', name: 'TEST_CONCURRENCY', value: '2'],
                [$class: 'StringParameterValue', name: 'TEST_PATTERN', value: ''],
                [$class: 'BooleanParameterValue', name: 'TESTRAIL', value: false],
                [$class: 'BooleanParameterValue', name: 'USE_PEPPER', value: false],
                [$class: 'StringParameterValue', name: 'PROJECT', value: 'smoke'],
                [$class: 'StringParameterValue', name: 'TEST_PASS_THRESHOLD', value: '100'],
                [$class: 'BooleanParameterValue', name: 'FAIL_ON_TESTS', value: true],
            ])
        }
    } catch (Exception e) {
        currentBuild.result = build_result
        throw e
    } finally {
        //
        // Clean
        //
        if (built_image) {
            sh("docker rmi -f ${image_full_name}")
        }
        dir(image_short_name) {
            deleteDir()
        }
        if (common.validInputParam('STACK_DELETE') && STACK_DELETE.toBoolean() == true) {
            try {
                if (!stack_name){
                    error('Stack cleanup parameters are undefined, cannot cleanup')
                }
                stage('Trigger cleanup job') {
                    common.errorMsg('Stack cleanup job triggered')
                    build(job: STACK_CLEANUP_JOB, parameters: [
                        [$class: 'StringParameterValue', name: 'STACK_NAME', value: stack_name],
                        [$class: 'StringParameterValue', name: 'STACK_TYPE', value: STACK_TYPE],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_URL', value: OPENSTACK_API_URL],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_CREDENTIALS', value: OPENSTACK_API_CREDENTIALS],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT', value: OPENSTACK_API_PROJECT],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT_DOMAIN', value: OPENSTACK_API_PROJECT_DOMAIN],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT_ID', value: OPENSTACK_API_PROJECT_ID],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_USER_DOMAIN', value: OPENSTACK_API_USER_DOMAIN],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_CLIENT', value: OPENSTACK_API_CLIENT],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_VERSION', value: OPENSTACK_API_VERSION],
                        [$class: 'BooleanParameterValue', name: 'DESTROY_ENV', value: true],
                    ])
                }
            } catch (Exception e) {
                common.errorMsg("Stack cleanup failed\n${e.message}")
            }
        }
    }
}
