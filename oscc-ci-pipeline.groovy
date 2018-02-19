/**
 * DEPLOY_JOB_NAME              Job name to deploy environmnets are going to be used for testes
 * DISTRIBUTION                 Distribution name for published repo. For example: dev-os-salt-formulas
 * COMPONENTS                   Components for repo. For example: salt
 * OPENSTACK_COMPONENTS_LIST    OpenStack related components list. For example: 'nova,cinder,glance,keystone,horizon,neutron,designate,heat,ironic,barbican'
 * prefixes                     Prefix packages to be published on the repo.  External storage can be passed with prefix for
 *                              example:
 *                                      def prefixes = ['oscc-dev', 's3:aptcdn:oscc-dev']
 * TMP_REPO_NODE_NAME           Node name where temp repo will be published
 * STACK_RECLASS_ADDRESS        URL for reclass model storage
 * OPENSTACK_RELEASES           OpenStack releases with comma delimeter which have to be testes. For example: pike,ocata
 * SOURCE_REPO_NAME             Name of the repo where packages are stored. For example: ubuntu-xenial-salt
 * APTLY_API_URL                URL to connect to aptly API. For example: http://172.16.48.254:8084
 * AUTO_PROMOTE                 True/False promote or not
 **/
common = new com.mirantis.mk.Common()
aptly = new com.mirantis.mk.Aptly()

node('python'){
    def server = [
        'url': APTLY_API_URL,
    ]
    def repo = SOURCE_REPO_NAME
    def components = COMPONENTS
    def prefixes = ['oscc-dev']
    def tmp_repo_node_name = TMP_REPO_NODE_NAME
//    def tmp_repo_node_name = 'apt.mcp.mirantis.net:8085'
//    def STACK_RECLASS_ADDRESS = 'https://gerrit.mcp.mirantis.net/salt-models/mcp-virtual-aio'
//    def OPENSTACK_RELEASES = 'ocata,pike'
//    def OPENSTACK_COMPONENTS_LIST = 'nova,cinder,glance,keystone,horizon,neutron,designate,heat,ironic,barbican'
    def notToPromote
//    def DEPLOY_JOB_NAME = 'oscore-MCP1.1-test-release-nightly'
    def testBuilds = [:]
    def deploy_release = [:]
    def distribution
    def snapshotDescription = 'OpenStack Core Components salt formulas CI'
    def snapshotName

    lock('aptly-api') {

        stage('Creating snapshot from nightly repo'){
            def now = new Date()
            def ts = now.format('yyyyMMddHHmmss', TimeZone.getTimeZone('UTC'))
            snapshotName = "os-salt-formulas-${ts}-oscc-dev"
            def nightlySnapshot = aptly.getSnapshotByAPI(server, 'nightly', 'xenial', components)
            def snapshotpkglist = aptly.snapshotPackagesByAPI(server, nightlySnapshot, OPENSTACK_COMPONENTS_LIST)

            snapshot = aptly.snapshotCreateByAPI(server, repo, snapshotName, snapshotDescription, snapshotpkglist)
            common.successMsg("Snapshot ${snapshotName} has been created for packages: ${snapshotpkglist}")
        }

        stage('Publishing the snapshots'){
            def now = new Date()
            def ts = now.format('yyyyMMddHHmmss', TimeZone.getTimeZone('UTC'))
            distribution = "${DISTRIBUTION}-${ts}"

            for (prefix in prefixes) {
                common.infoMsg("Publishing ${distribution} for prefix ${prefix} is started.")
                aptly.snapshotPublishByAPI(server, snapshotName, distribution, components, prefix)
                common.successMsg("Snapshot ${snapshotName} has been published for prefix ${prefix}")
            }
        }
    }

    stage('Deploying environment and testing'){
        for (openstack_release in OPENSTACK_RELEASES.tokenize(',')) {
            def release = openstack_release
            deploy_release["OpenStack ${release} deployment"] = {
                node('oscore-testing') {
                    testBuilds["${release}"] = build job: "${DEPLOY_JOB_NAME}-${release}", propagate: false, parameters: [
                        [$class: 'StringParameterValue', name: 'BOOTSTRAP_EXTRA_REPO_PARAMS', value: "deb [arch=amd64] http://${tmp_repo_node_name}/oscc-dev ${distribution} ${components},1300,release n=${distribution}"],
                        [$class: 'StringParameterValue', name: 'FORMULA_PKG_REVISION', value: 'stable'],
                        [$class: 'BooleanParameterValue', name: 'STACK_DELETE', value: STACK_DELETE.toBoolean()],
                        [$class: 'StringParameterValue', name: 'STACK_RECLASS_ADDRESS', value: STACK_RECLASS_ADDRESS],
                        [$class: 'StringParameterValue', name: 'STACK_RECLASS_BRANCH', value: "stable/${release}"],
                    ]
                }
            }
        }
    }

    stage('Running parallel OpenStack deployment') {
        parallel deploy_release
    }

    stage('Managing deployment results') {
        for (k in testBuilds.keySet()) {
            if (testBuilds[k].result != 'SUCCESS') {
                notToPromote = true
                common.errorMsg("${k} : " + testBuilds[k].result)
            } else {
                common.successMsg("${k} : " + testBuilds[k].result)
            }
        }

    }

   stage('Promotion to testing repo'){
       if (notToPromote) {
            error('Snapshot can not be promoted!!!')
       }
       if (common.validInputParam('AUTO_PROMOTE') && AUTO_PROMOTE.toBoolean() == true) {
            for (prefix in prefixes) {
                common.successMsg("${components} repo with prefix: ${prefix} distribution: ${distribution} snapshot: ${snapshotName} will be promoted to testing")
                aptly.promotePublish(server['url'], "${prefix}/${distribution}", 'xenial/testing', 'false', components, OPENSTACK_COMPONENTS_LIST, '', '-d --timeout 1200', '', '')
            }
       }
   }
}
