#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    parameters([
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
                    description: '''Credentials ID for a JSON file passed as \
the GOOGLE_APPLICATION_CREDENTIALS value for downloading release files from \
the Google Storage URL, requires read permission''',
                    name: 'DOWNLOAD_CREDS',
                    required: true),
        string(name: 'DOWNLOAD_ROOT',
                defaultValue: 'gs://builds.developer.core-os.net',
                description: 'URL prefix where image files are downloaded'),
	    choice(name: 'OCI_SHAPE',
               choices: "VM.Standard1.1\nVM.Standard1.4\nVM.Standard1.8\nVM.Standard1.16",
               description: 'OCI shape to test'),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: '17942f6a-969b-466d-ac46-cd15925c8953',
                    description: 'Config required by "kola run --platform=oci"',
                    name: 'OCI_TEST_CONFIG',
                    required: true),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'f7121197-c5b2-4e67-8529-ff59224bef91',
                    description: 'RSA Key in PEM format referenced by config',
                    name: 'OCI_TEST_KEY',
                    required: true),
        text(name: 'TORCX_MANIFEST',
             defaultValue: '',
             description: 'Contents of the torcx manifest for kola tests'),
        text(name: 'VERIFY_KEYRING',
             defaultValue: '',
             description: '''ASCII-armored keyring containing the public keys \
used to verify signed files and Git tags'''),
        string(name: 'VERSION',
               defaultValue: '',
               description: 'OS image version to use'),
        string(name: 'PIPELINE_BRANCH',
               defaultValue: 'master',
               description: 'Branch to use for fetching the pipeline jobs')
    ])
])

/* The kola step doesn't fail the job, so save the return code separately.  */
def rc = 0

node('coreos && amd64 && sudo') {
    stage('Build') {
        step([$class: 'CopyArtifact',
              fingerprintArtifacts: true,
              projectName: '/mantle/master-builder',
              selector: [$class: 'StatusBuildSelector', stable: false]])

        writeFile file: 'torcx_manifest.json', text: params.TORCX_MANIFEST ?: ''

        withCredentials([
            file(credentialsId: params.OCI_TEST_CONFIG, variable: 'OCI_TEST_CONFIG'),
            file(credentialsId: params.OCI_TEST_KEY, variable: 'OCI_TEST_KEY'),
            file(credentialsId: params.DOWNLOAD_CREDS, variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
            withEnv(["BOARD=amd64-usr",
                     "DOWNLOAD_ROOT=${params.DOWNLOAD_ROOT}",
                     "OCI_SHAPE=${params.OCI_SHAPE}",
                     "VERSION=${params.VERSION}"]) {
                rc = sh returnStatus: true, script: '''#!/bin/bash -ex

rm -rf *.tap _kola_temp _kola_temp*

rm -rf .oci
mkdir --mode=0700 .oci
mv ${OCI_TEST_CONFIG} .oci/config
mv ${OCI_TEST_KEY} .oci/oci_api_key.pem
touch .oci/config.mantle
chmod 0600 .oci/*
trap 'rm -rf .oci' EXIT

NAME="jenkins-${JOB_NAME##*/}-${BUILD_NUMBER}"

mkdir -p src
bin/cork download-image \
    --root="${DOWNLOAD_ROOT}/boards/${BOARD}/${VERSION}" \
    --json-key="${GOOGLE_APPLICATION_CREDENTIALS}" \
    --cache-dir=./src \
    --platform=oci \
    --verify=true $verify_key

image_id=$(bin/ore oci upload \
    --bucket image-upload \
    --file src/coreos_production_oracle_oci_qcow_image.img)

trap 'bin/ore oci delete-image --image "${image_id}" && rm -rf .oci' EXIT

timeout --signal=SIGQUIT 300m kola run \
    --parallel=1 \
    --basename="${NAME}" \
    --oci-image="${image_id}" \
    --oci-shape="${OCI_SHAPE}" \
    --platform=oci \
    --tapfile="${JOB_NAME##*/}.tap" \
    --torcx-manifest=torcx_manifest.json
'''  /* Editor quote safety: ' */

                message = sh returnStdout: true, script: '''jq '.tests[] | select(.result == "FAIL") | .name' -r < _kola_temp/oci-latest/reports/report.json | sed -e :a -e '$!N; s/\\n/, /; ta' '''
            }
        }
    }

    stage('Post-build') {
        step([$class: 'TapPublisher',
              discardOldReports: false,
              enableSubtests: true,
              failIfNoResults: true,
              failedTestsMarkBuildAsFailure: true,
              flattenTapResult: false,
              includeCommentDiagnostics: true,
              outputTapToConsole: true,
              planRequired: true,
              showOnlyFailures: false,
              skipIfBuildNotOk: false,
              stripSingleParents: false,
              testResults: '*.tap',
              todoIsFailure: false,
              validateNumberOfTests: true,
              verbose: true])

        sh 'tar -cJf _kola_temp.tar.xz _kola_temp'
        archiveArtifacts '_kola_temp.tar.xz'
    }
}

/* Propagate the job status after publishing TAP results.  */
currentBuild.result = rc == 0 ? 'SUCCESS' : 'FAILURE'

if (currentBuild.result == 'FAILURE')
    slackSend color: 'danger',
              message: "```Kola: OCI-amd64 Failure: <${BUILD_URL}console|Console> - <${BUILD_URL}artifact/_kola_temp.tar.xz|_kola_temp>\n$message```"
