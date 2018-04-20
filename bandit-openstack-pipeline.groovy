/**
 *
 * Pipeline which runs bandit scan against gerrit project and generates report
 * Two modes are supported - downstream and upstream (allows to choose scan criteria)
 * Pipeline stages:
 *  - Checkout of repository with project code
 *  - Preparing Bandit virtual env and settings for scan
 *  - Bandit scan of target code diretories
 *  - If report format is json, upload it and csv generated report to reporting tool
 *  - Archieving report to artifacts.
 *
 * Flow parameters:
 *   FAIL_ON_TESTS                     Whether to fail build on bandit tests failures or not
 *   GERRIT_PROJECT_URL                URL to gerrit project repository with gerrit parameters
 *                                     (can be used instead of GERRIT_* parameters)
 *   GERRIT_BRANCH                     Gerrit project branch
 *   GERRIT_USER                       User to us to connect to gerrit
 *   GERRIT_SCHEME                     Scheme to use to connect to gerrit (ssh, http, https)
 *   GERRIT_HOST                       Hostname of gerrit host
 *   GERRIT_PORT                       Port to us to connect to gerrit
 *   GERRIT_PROJECT                    Project to clone from gerrit (e. g. openstack/nova)
 *   SEVERITY                          Severity setting for bandit (used in downstream mode - e.g.
 *                                     1 - low, 2 - medium, 3 - high)
 *   CONFIDENCE                        Confidence setting for bandit (used in downstream mode - e.g.
 *                                     1 - low, 2 - medium, 3 - high)
 *   UPSTREAM                          Whether to work in upstream or downstream mode
 *   UPLOAD_REPORT                     Whether to upload report to reporting tool
 *   UPLOAD_CREDENTIALS_ID             Jenkins credentials id for connection to report host
 *   REPORT_FORMAT                     Format of generated report (json, csv, html)
 *   REPORT_HOST                       Host to upload report on
 *   REPORT_USER                       User for report uploading to report host *
 **/
def ssh = new com.mirantis.mk.Ssh()
def gerrit = new com.mirantis.mk.Gerrit()
def git = new com.mirantis.mk.Git()
def python = new com.mirantis.mk.Python()
def common = new com.mirantis.mk.Common()

// TODO: move to pipeline-library
def runShCommandStatus(cmd){
    def common = new com.mirantis.mk.Common()
    def res = [:]
    def stderr = sh(script: 'mktemp', returnStdout: true).trim()
    def stdout = sh(script: 'mktemp', returnStdout: true).trim()

    try {
        common.infoMsg("Run command ${cmd}")
        def status = sh(script: "${cmd} 1>${stdout} 2>${stderr}", returnStatus: true)
        res['stderr'] = sh(script: "cat ${stderr}", returnStdout: true)
        res['stdout'] = sh(script: "cat ${stdout}", returnStdout: true)
        res['status'] = status
    } finally {
        sh(script: "rm ${stderr}", returnStdout: true)
        sh(script: "rm ${stdout}", returnStdout: true)
    }

    return res
}

def runVirtualenvCommandStatus(path, cmd) {
    return runShCommandStatus(". ${path}/bin/activate > /dev/null; ${cmd}")
}

def runBanditTests(venv, target=[], excludes=[], reportPath='', reportFormat='csv', severity=0, confidence=0) {
    def target_str
    // Bandit doesn't fail if target path doesn't exist
    if (target) {
        for (t in target){
            if (!fileExists(t)){
                error("Target path ${t} doesn't exist, nothing to scan!")
            }
        }
        target_str = target.join(' ')
    } else {
        error('No target specified!')
    }

    def banditArgs = ["-r ${target_str}" + ' -l' * severity + ' -i' * confidence]

    if (excludes){
        banditArgs.add("-x ${excludes.join(',')}")
    }
    if (reportPath){
        banditArgs.addAll(["-o ${reportPath}", "-f ${reportFormat}"])
    }

    return runVirtualenvCommandStatus(venv, "bandit ${banditArgs.join(' ')}")
}

// there is no native ini parser in groovy
def getIniParamValue(path, section, param){

    def parserFile = "${env.WORKSPACE}/iniFileParser.py"
    def parserScript = """
import ConfigParser
import sys

path = sys.argv[1]
section = sys.argv[2]
param = sys.argv[3]

config = ConfigParser.ConfigParser()
config.read([path])

val = config.get(section, param)
sys.stdout.write(val.strip()+'\\n')
"""

    writeFile file: parserFile, text: parserScript
    return runShCommandStatus("python ${parserFile} ${path} ${section} ${param}")
}

def writeBanditCsvReport(csv, columns, data = []) {
    def common = new com.mirantis.mk.Common()
    def encode = { e -> "\"$e\"" }
    def columns_encoded = []

    // encode columns to csv valid form e.g. "column"
    for (i = 0; i < columns.size(); i++) {
        columns_encoded.add(encode(columns[i]))
    }
    def csv_list = [columns_encoded.join(',')]

    // generate list of csv rows
    if (data) {
        for (i in data) {
            row = []
            for (col in columns) {
                row.add(encode(i[col]))
            }
            csv_list.add(row.join(','))
        }
    } else {
        csv_list.add(encode('No issues found'))
    }

    common.infoMsg('Generating Bandit CSV report from json')
    writeFile file: csv, text: csv_list.join('\n')
}

node('oscore-testing'){

    def build_result = 'FAILURE'
    if (!FAIL_ON_TESTS.toBoolean()){
        build_result = 'SUCCESS'
    }
    def project_url = GERRIT_PROJECT_URL ?: "${GERRIT_SCHEME}://${GERRIT_USER}@${GERRIT_HOST}:${GERRIT_PORT}/${GERRIT_PROJECT}"
    // get simple project name from GERRIT_PROJECT_URL
    // (e.g ssh://oscc-ci@review.fuel-infra.org:29418/openstack/nova - nova)
    // also removes '.git' from the end of the project name if any.
    def project_name = project_url.tokenize('/').last() - ~/\.git$/
    def project_path = "${env.WORKSPACE}/${project_name}"
    def artifacts_dir = '_artifacts'
    def report_path = "${env.WORKSPACE}/${artifacts_dir}/report-${project_name}.${REPORT_FORMAT}"
    def venv = "${env.WORKSPACE}/bandit_${project_name}"
    def ini_res
    def version = GERRIT_BRANCH.tokenize('/').last()
    def upload_report = false
    def remote_root = '/var/www/bandit'
    def remote_upload_path = "${remote_root}/${version}/${project_name}"

    sh("rm -rf ${artifacts_dir} && mkdir -p ${artifacts_dir}")

    dir(project_path) {
        deleteDir()
        stage('Checkout'){
            if (common.validInputParam('GERRIT_REFSPEC')){
                gerrit.gerritPatchsetCheckout(project_url, GERRIT_REFSPEC, GERRIT_BRANCH, CREDENTIALS_ID)
            } else {
                git.checkoutGitRepository(project_path, project_url, GERRIT_BRANCH, CREDENTIALS_ID)
            }
        }

        stage('Preparing Bandit environment and settings'){
            if (UPSTREAM.toBoolean()) {
                // Trying to get bandit command from upstream tox.ini
                if (project_name == 'glance'){
                    // glance bandit is described in the pep8 env
                    ini_res = getIniParamValue('tox.ini', 'testenv:pep8', 'commands')
                    def m = ini_res['stdout'] =~ /(?m)^bandit .*$/
                    ini_res['stdout'] = m[0]
                } else {
                    ini_res = getIniParamValue('tox.ini', 'testenv:bandit', 'commands')
                }
                def ini_err = ini_res['stderr']
                if (ini_res['status'] > 0){
                    if (ini_err.contains('ConfigParser.NoSectionError')){
                        currentBuild.result = 'NOT_BUILT'
                        error("Bandit tests for ${project_name} aren't implemented in upstream yet\n${ini_err}")
                    }
                    error("Failed to get bandit command\n${ini_err}")
                }
            } else {
                // Get list of directories with python code for downstream scan
                ini_res = getIniParamValue('setup.cfg', 'files', 'packages')
            }
            python.setupVirtualenv(venv, 'python2', ['bandit'], null, true)
        }

        stage('Bandit scan'){
            def res

            if (UPSTREAM.toBoolean()) {
                common.infoMsg("Running upstream Bandit tests for ${project_name}")
                // Currently upstream implementation of bandit doesn't generate report
                res = runVirtualenvCommandStatus(venv, "${ini_res['stdout'].trim()} -o ${report_path} -f ${REPORT_FORMAT}")
            } else {
                if (common.validInputParam('UPLOAD_REPORT')){
                    upload_report = UPLOAD_REPORT.toBoolean()
                }
                def code_dirs = ini_res['stdout'].tokenize('\n')
                def excluded = /(.*tempest_plugin.*)|(heat_integrationtests)/
                // Exclude upper directories which shouldn't be scanned (tempest plugins etc.)
                code_dirs.removeAll { it ==~ excluded }
                common.infoMsg("Running downstream Bandit tests for ${project_name} in directories ${code_dirs}")
                res = runBanditTests(venv, code_dirs, ['tests'], "${report_path}",
                    REPORT_FORMAT, SEVERITY.toInteger(), CONFIDENCE.toInteger())
            }

            common.infoMsg("BANDIT OUTPUT:\n${res['stdout'] + res['stderr']}")

            if (res['status'] == 1){
                common.errorMsg('''
                    -------------------------------------------------------------
                    !!! Security violations found, please check bandit report !!!
                    -------------------------------------------------------------
                    '''
                )
                currentBuild.result = build_result
            } else if (res['status'] > 1){
                common.errorMsg('Bandit tests failed')
                currentBuild.result = 'FAILURE'
                // do not upload report if there were errors in bandit
                upload_report = false
            }
        }
    }

    try {
        if (REPORT_FORMAT == 'json') {
            // read json file in hashmap
            def report_data = readJSON file: "${report_path}"
            def columns = ['filename', 'test_name', 'test_id', 'issue_severity', 'issue_confidence', 'issue_text', 'line_number', 'line_range']
            writeBanditCsvReport("${env.WORKSPACE}/${artifacts_dir}/report-${project_name}.csv", columns, report_data['results'])
            if (upload_report) {
                def report_host_url = "${REPORT_USER}@${REPORT_HOST}"
                stage ('Uploading reports') {
                    ssh.ensureKnownHosts(REPORT_HOST)
                    ssh.prepareSshAgentKey(UPLOAD_CREDENTIALS_ID)
                    ssh.runSshAgentCommand("ssh ${report_host_url} 'mkdir -p ${remote_upload_path}'")
                    ssh.runSshAgentCommand("scp ${artifacts_dir}/report-${project_name}.* ${report_host_url}:${remote_upload_path}")
                }
            }
        }
    } finally {
        stage("Archieving bandit scan results for ${project_name}"){
            archiveArtifacts artifacts: "${artifacts_dir}/report-${project_name}.*"
        }
    }
}
