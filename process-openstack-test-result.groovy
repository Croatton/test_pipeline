
/**
 *
 * Pipeline for test results processing.
 * Pipeline stages:
 *  - Downloading of tests results from specified job.
 *  - Upload results to testrail (Optional).
 *  - Analysing results and making a decision about build success.
 *
 * Expected parameters:
 *   FAIL_ON_TESTS                Whether to fail build on tests failures or not
 *   OPENSTACK_VERSION            Version of Openstack being tested
 *   TARGET_JOB                   Name of the testing job
 *   TARGET_BUILD_NUMBER          Number of the testing build
 *   TEST_REPORTER_IMAGE          Docker image for testrail reporter
 *   TESTRAIL                     Whether upload results to testrail or not
 *   TEST_MILESTONE               Product version for tests
 *   TEST_MODEL                   Salt model used in environment
 *   TEST_SUITE                   Testrail test suite
 *   TEST_PLAN                    Testrail test plan
 *   TEST_GROUP                   Testrail test group
 *   TESTRAIL_QA_CREDENTIALS      Credentials for upload to testrail
 *   TEST_DATE                    Date of test run
 *   TEST_PASS_THRESHOLD          Persent of passed tests to consider build successful
 *
 */

common = new com.mirantis.mk.Common()
test = new com.mirantis.mk.Test()

node('docker') {

    def testOutputDir = sh(script: 'mktemp -d', returnStdout: true).trim()

    try {
        //TODO: Implement support for stepler run artifacts
        stage('Get tests artifacts') {
            def selector = [$class: 'SpecificBuildSelector', buildNumber: "${TARGET_BUILD_NUMBER}"]

            step ([$class: 'CopyArtifact',
                   projectName: TARGET_JOB,
                   selector: selector,
                   filter: '_artifacts/test.tar',
                   target: testOutputDir,
                   flatten: true,])

            dir(testOutputDir) {
                sh('tar -xf test.tar')
            }
        }

        def report = sh(script: "find ${testOutputDir} -name *.xml", returnStdout: true).trim()

        if (common.validInputParam('TESTRAIL') && TESTRAIL.toBoolean()) {
            stage('Upload tests results to Testrail'){
                def test_suite = TEST_SUITE

                // Docker run fails if TEST_SUITE has whitespaces
                if (test_suite[0] != "'" && test_suite[-1] != "'"){
                    test_suite = "'${test_suite}'"
                }

                def plan = TEST_PLAN ?: "${TEST_MILESTONE}-OSCORE-${TEST_DATE}"
                def group = TEST_GROUP ?: "${TEST_MODEL}-${OPENSTACK_VERSION}-nightly"

                //ensures that we have up to date image on jenkins slave
                sh("docker pull ${TEST_REPORTER_IMAGE}")

                test.uploadResultsTestrail(report, TEST_REPORTER_IMAGE, group, TESTRAIL_QA_CREDENTIALS,
                    plan, TEST_MILESTONE, test_suite)
            }
        }

        //TODO: use xunit publisher plugin to publish results
        stage('Check tests results'){
            def fileContents = sh(script: "cat ${report}", returnStdout: true).trim()
            def parsed = new XmlParser().parseText(fileContents)
            def res = parsed.attributes()

            def failed = res.failures.toInteger()
            def tests = res.tests.toInteger()
            def skipped = res.skipped.toInteger()
            def passed = tests - failed - skipped

            def pr = (passed / (tests - skipped)) * 100

            println("""\
                    Failed:  ${res.failures}
                    Errors:  ${res.errors}
                    Skipped: ${res.skipped}
                    Tests:   ${res.tests}
                    TESTS PASS RATE: ${pr}%
                    """)

            if (pr < TEST_PASS_THRESHOLD.toInteger() && FAIL_ON_TESTS.toBoolean()){
                error("Pass rate ${pr}% is lower than pass threshold ${TEST_PASS_THRESHOLD}%")
            }
        }
    } catch (Exception e) {
        currentBuild.result = 'FAILURE'
        throw e
    } finally {
        dir(testOutputDir) {
          deleteDir()
        }
    }
}
