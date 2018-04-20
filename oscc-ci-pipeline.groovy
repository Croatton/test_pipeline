/**
 * DEPLOY_JOB_NAME              Job name to deploy environmnets are going to be used for testes
 * DISTRIBUTION                 Distribution name for published repo. For example: dev-os-salt-formulas
 * COMPONENTS                   Components for repo. For example: salt
 * OPENSTACK_COMPONENTS_LIST    OpenStack related components list. For example: 'nova,cinder,glance,keystone,horizon,neutron,designate,heat,ironic,barbican'
 * prefixes                     Prefix packages to be published on the repo.  External storage can be passed with prefix for
 *                              example:
 *                                      def prefixes = ['oscc-dev', 's3:aptcdn:oscc-dev']
 * TMP_REPO_NODE_NAME           Node name where temp repo will be published
 * SOURCE_REPO_NAME             Name of the repo where packages are stored. For example: ubuntu-xenial-salt
 * APTLY_API_URL                URL to connect to aptly API. For example: http://172.16.48.254:8084
 * MULTINODE_JOB                Job name to deploy multi-node model
 * TEST_SCHEMAS                 Defines structure to pass model, cluster_name, branch to run tests on it.
 *                              For example 'aio:cluster-name1:branch1,branch2|multinode:cluster-name2:branch1,branch2'
 * AUTO_PROMOTE                 True/False promote or not
 **/
common = new com.mirantis.mk.Common()
aptly = new com.mirantis.mk.Aptly()

import java.util.regex.Pattern;

@NonCPS
def getRemoteStorage(String prefix) {
    def regex = Pattern.compile('(^.*):')
    def matcher = regex.matcher(prefix)
    if(matcher.find()){
        def storageName = matcher.group(1)
        return storageName
    }else{
        return ''
    }
}

def getTestSchemas(testSchemas){
    def schemas = testSchemas.tokenize('|')
    def aio_schemas = []
    def multinode_schemas = []
    def cluster_branch
    for (schema in schemas){
        if ( schema.tokenize(':')[0] == 'aio' ){
            aio_schemas.add(
              ['cluster_name': schema.tokenize(':')[1],
               'branches': schema.tokenize(':')[2].tokenize(',')]
            )
        } else if (schema.tokenize(':')[0] == 'multinode' ){
            multinode_schemas.add(
              ['cluster_name': schema.tokenize(':')[1],
               'branches': schema.tokenize(':')[2].tokenize(',')]
            )
        }

    }
    return ['aio_schemas': aio_schemas, 'multinode_schemas': multinode_schemas]
}

def testSchemas
def multinod_job = 'oscore-test_virtual_model'
if (common.validInputParam('MULTINODE_JOB')) {
    multinod_job = MULTINODE_JOB
}

timeout(time: 6, unit: 'HOURS') {
    node('oscore-testing'){
        def server = [
            'url': APTLY_API_URL,
        ]
        def repo = SOURCE_REPO_NAME
        def components = COMPONENTS
        def prefixes = ['oscc-dev','s3:aptcdn:oscc-dev']
        def tmp_repo_node_name = TMP_REPO_NODE_NAME
        def notToPromote
        def notToPromoteMulti
        def testBuilds = [:]
        def testBuildsMulti = [:]
        def deploy_release = [:]
        def snapshotDescription = 'OpenStack Core Components salt formulas CI'
        def now = new Date()
        def ts = now.format('yyyyMMddHHmmss', TimeZone.getTimeZone('UTC'))
        def snapshotName = "os-salt-formulas-${ts}-oscc-dev"
        def distribution = "${DISTRIBUTION}-${ts}"
        def storage
        def testClusterNames

        lock('aptly-api') {

            stage('Creating snapshot from nightly repo'){
                def nightlySnapshot = aptly.getSnapshotByAPI(server, 'nightly', 'xenial', components)
                def snapshotpkglist = aptly.snapshotPackagesByAPI(server, nightlySnapshot, OPENSTACK_COMPONENTS_LIST)

                snapshot = aptly.snapshotCreateByAPI(server, repo, snapshotName, snapshotDescription, snapshotpkglist)
                common.successMsg("Snapshot ${snapshotName} has been created for packages: ${snapshotpkglist}")
            }

            stage('Publishing the snapshots'){
                for (prefix in prefixes) {
                    common.infoMsg("Publishing ${distribution} for prefix ${prefix} is started.")
                    aptly.snapshotPublishByAPI(server, snapshotName, distribution, components, prefix)
                    common.successMsg("Snapshot ${snapshotName} has been published for prefix ${prefix}")
                }
            }
        }

        testSchemas = getTestSchemas(TEST_SCHEMAS)
        stage('Deploying environment and testing'){
            def testSchemasAIO = testSchemas['aio_schemas']
            common.infoMsg("Running AIO deployment on the following schemas: ${testSchemasAIO}")
            for (schema in testSchemasAIO){
                for (branch in schema['branches']){
                    def cn = schema['cluster_name']
                    def release = branch.tokenize('/')[1]

                    deploy_release["Deploy ${cn} ${release}"] = {
                        node('oscore-testing') {
                            testBuilds["${cn}-${release}"] = build job: "${DEPLOY_JOB_NAME}-${release}", propagate: false, parameters: [
                                [$class: 'StringParameterValue', name: 'BOOTSTRAP_EXTRA_REPO_PARAMS', value: "deb [arch=amd64] http://${tmp_repo_node_name}/oscc-dev ${distribution} ${components},1300,release n=${distribution}"],
                                [$class: 'StringParameterValue', name: 'FORMULA_PKG_REVISION', value: 'stable'],
                                [$class: 'StringParameterValue', name: 'STACK_CLUSTER_NAME', value: cn],
                                [$class: 'BooleanParameterValue', name: 'STACK_DELETE', value: STACK_DELETE.toBoolean()],
                                [$class: 'StringParameterValue', name: 'STACK_RECLASS_BRANCH', value: "stable/${release}"],
                            ]
                        }
                    }
                }
            }

            def testSchemasMultinode = testSchemas['multinode_schemas']
            common.infoMsg("Running Multinode deployment on the following schemas: ${testSchemasMultinode}")
            for (schema in testSchemasMultinode){
                for (branch in schema['branches']){
                    def cn = schema['cluster_name']
                    def br = branch

                    deploy_release["Deploy ${cn} ${branch}"] = {
                        node('oscore-testing') {
                            testBuildsMulti["${cn}-${br}"] = build job: multinod_job, propagate: false, parameters: [
                                [$class: 'StringParameterValue', name: 'STACK_CLUSTER_NAME', value: cn],
                                [$class: 'StringParameterValue', name: 'FORMULA_PKG_REVISION', value: "testing"],
                                [$class: 'BooleanParameterValue', name: 'RUN_SMOKE', value: false],
                                [$class: 'StringParameterValue', name: 'BOOTSTRAP_EXTRA_REPO_PARAMS', value: "deb [arch=amd64] http://${tmp_repo_node_name}/oscc-dev ${distribution} ${components},1300,release n=${distribution}"],
                                [$class: 'BooleanParameterValue', name: 'STACK_DELETE', value: STACK_DELETE.toBoolean()],
                                [$class: 'StringParameterValue', name: 'STACK_RECLASS_BRANCH', value: br],
                            ]
                        }
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
            for (k in testBuildsMulti.keySet()) {
                if (testBuildsMulti[k].result != 'SUCCESS') {
                    notToPromoteMulti = true
                    common.errorMsg("${k} : " + testBuildsMulti[k].result)
                } else {
                    common.successMsg("${k} : " + testBuildsMulti[k].result)
                }
            }

        }

       stage('Promotion to testing repo'){
           if (notToPromote || notToPromoteMulti) {
                error('Snapshot can not be promoted!!!')
           }
           if (common.validInputParam('AUTO_PROMOTE') && AUTO_PROMOTE.toBoolean() == true) {
                for (prefix in prefixes) {
                    storage = getRemoteStorage(prefix)
                    common.successMsg("${components} repo with prefix: ${prefix} distribution: ${distribution} snapshot: ${snapshotName} will be promoted to testing")
                    aptly.promotePublish(server['url'], "${prefix}/${distribution}", 'xenial/testing', 'false', components, OPENSTACK_COMPONENTS_LIST, '', '-d --timeout 1200', '', "${storage}")
                }
           }
       }
    }
}
