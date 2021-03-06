#!groovy

properties([
    buildDiscarder(logRotator(daysToKeepStr: '30', numToKeepStr: '50')),

    parameters([
        string(name: 'AWS_REGION',
               defaultValue: 'us-west-2',
               description: 'AWS region to use for AMIs and testing'),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: '1bb768fc-940d-4a95-95d0-27c1153e7fa0',
                    description: 'AWS credentials list for AMI creation and releasing',
                    name: 'AWS_RELEASE_CREDS',
                    required: true),
        credentials(credentialType: 'com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsImpl',
                    defaultValue: '6d37d17c-503e-4596-9a9b-1ab4373955a9',
                    description: 'Credentials with permissions required by "kola run --platform=aws"',
                    name: 'AWS_TEST_CREDS',
                    required: true),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: '7ab88376-e794-4128-b644-41c83c89e76d',
                    description: 'JSON credentials file for all Azure clouds used by plume',
                    name: 'AZURE_CREDS',
                    required: true),
        choice(name: 'BOARD',
               choices: "amd64-usr\narm64-usr",
               description: 'Target board to build'),
        string(name: 'GROUP',
               defaultValue: 'developer',
               description: 'Which release group owns this build'),
        credentials(credentialType: 'com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey',
                    defaultValue: '',
                    description: 'Credential ID for SSH Git clone URLs',
                    name: 'BUILDS_CLONE_CREDS',
                    required: false),
        choice(name: 'COREOS_OFFICIAL',
               choices: "0\n1"),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
                    description: '''Credentials ID for a JSON file passed as \
the GOOGLE_APPLICATION_CREDENTIALS value for uploading development files to \
the Google Storage URL, requires write permission''',
                    name: 'GS_DEVEL_CREDS',
                    required: true),
        string(name: 'GS_DEVEL_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where development files are uploaded'),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'jenkins-coreos-systems-write-5df31bf86df3.json',
                    description: '''Credentials ID for a JSON file passed as \
the GOOGLE_APPLICATION_CREDENTIALS value for uploading release files to the \
Google Storage URL, requires write permission''',
                    name: 'GS_RELEASE_CREDS',
                    required: true),
        string(name: 'GS_RELEASE_DOWNLOAD_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where release files are downloaded'),
        string(name: 'GS_RELEASE_ROOT',
               defaultValue: 'gs://builds.developer.core-os.net',
               description: 'URL prefix where release files are uploaded'),
        string(name: 'MANIFEST_NAME',
               defaultValue: 'release.xml'),
        string(name: 'MANIFEST_TAG',
               defaultValue: ''),
        string(name: 'MANIFEST_URL',
               defaultValue: 'https://github.com/coreos/manifest-builds.git'),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl',
                    defaultValue: 'd67b5bde-d138-487a-9da3-0f5f5f157310',
                    description: 'Credentials to run hosts in PACKET_PROJECT',
                    name: 'PACKET_CREDS',
                    required: true),
        string(name: 'PACKET_PROJECT',
               defaultValue: '9da29e12-d97c-4d6e-b5aa-72174390d57a',
               description: 'The Packet project ID to run test machines'),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl',
                    defaultValue: 'buildbot-official.EF4B4ED9.subkey.gpg',
                    description: 'Credential ID for a GPG private key file',
                    name: 'SIGNING_CREDS',
                    required: true),
        string(name: 'SIGNING_USER',
               defaultValue: 'buildbot@coreos.com',
               description: 'E-mail address to identify the GPG key'),
        text(name: 'VERIFY_KEYRING',
             defaultValue: '',
             description: '''ASCII-armored keyring containing the public keys \
used to verify signed files and Git tags'''),
        string(name: 'PIPELINE_BRANCH',
               defaultValue: 'master',
               description: 'Branch to use for fetching the pipeline jobs')
    ])
])

/* The unsigned image generated here is still a dev file with Secure Boot.  */
def UPLOAD_CREDS = params.GS_RELEASE_CREDS
def UPLOAD_ROOT = params.GS_RELEASE_ROOT
if (false && params.COREOS_OFFICIAL == '1') {
    UPLOAD_CREDS = params.GS_DEVEL_CREDS
    UPLOAD_ROOT = params.GS_DEVEL_ROOT
}

/* Get the list of image formats from the scripts repository.  */
String format_list = ''

node('coreos && amd64 && sudo') {
    stage('Build') {
        step([$class: 'CopyArtifact',
              fingerprintArtifacts: true,
              projectName: '/mantle/master-builder',
              selector: [$class: 'StatusBuildSelector', stable: false]])

        writeFile file: 'verify.asc', text: params.VERIFY_KEYRING ?: ''

        sshagent(credentials: [params.BUILDS_CLONE_CREDS], ignoreMissing: true) {
            withCredentials([
                file(credentialsId: params.GS_DEVEL_CREDS, variable: 'GS_DEVEL_CREDS'),
                file(credentialsId: params.SIGNING_CREDS, variable: 'GPG_SECRET_KEY_FILE'),
                file(credentialsId: UPLOAD_CREDS, variable: 'GOOGLE_APPLICATION_CREDENTIALS'),
            ]) {
                withEnv(["BOARD=${params.BOARD}",
                         "COREOS_OFFICIAL=${params.COREOS_OFFICIAL}",
                         "DOWNLOAD_ROOT=${params.GS_DEVEL_ROOT}",
                         "GROUP=${params.GROUP}",
                         "MANIFEST_NAME=${params.MANIFEST_NAME}",
                         "MANIFEST_TAG=${params.MANIFEST_TAG}",
                         "MANIFEST_URL=${params.MANIFEST_URL}",
                         "SIGNING_USER=${params.SIGNING_USER}",
                         "UPLOAD_ROOT=${UPLOAD_ROOT}"]) {
                    sh '''#!/bin/bash -ex

# The build may not be started without a tag value.
[ -n "${MANIFEST_TAG}" ]

# Set up GPG for verifying tags.
export GNUPGHOME="${PWD}/.gnupg"
rm -rf "${GNUPGHOME}"
trap 'rm -rf "${GNUPGHOME}"' EXIT
mkdir --mode=0700 "${GNUPGHOME}"
gpg --import verify.asc

bin/cork update \
    --create --downgrade-replace --verify --verify-signature --verbose \
    --manifest-branch "refs/tags/${MANIFEST_TAG}" \
    --manifest-name "${MANIFEST_NAME}" \
    --manifest-url "${MANIFEST_URL}"

# Run branch-specific build commands from the scripts repository.
. src/scripts/jenkins/images.sh
'''
                }
            }
        }
    }

    stage('Post-build') {
        fingerprint "chroot/build/${params.BOARD}/var/lib/portage/pkgs/*/*.tbz2,chroot/var/lib/portage/pkgs/*/*.tbz2,src/build/images/${params.BOARD}/latest/*"
        dir('src/build') {
            deleteDir()
        }
        format_list = readFile "src/scripts/jenkins/formats-${params.BOARD}.txt"
    }
}

stage('Downstream') {
    parallel failFast: false,
        'board-vm-matrix': {
            if (false && params.COREOS_OFFICIAL == '1')
                build job: 'sign-image', parameters: [
                    string(name: 'AWS_REGION', value: params.AWS_REGION),
                    credentials(name: 'AWS_RELEASE_CREDS', value: params.AWS_RELEASE_CREDS),
                    credentials(name: 'AWS_TEST_CREDS', value: params.AWS_TEST_CREDS),
                    credentials(name: 'AZURE_CREDS', value: params.AZURE_CREDS),
                    string(name: 'BOARD', value: params.BOARD),
                    credentials(name: 'BUILDS_CLONE_CREDS', value: params.BUILDS_CLONE_CREDS),
                    string(name: 'GROUP', value: params.GROUP),
                    credentials(name: 'GS_DEVEL_CREDS', value: params.GS_DEVEL_CREDS),
                    string(name: 'GS_DEVEL_ROOT', value: params.GS_DEVEL_ROOT),
                    credentials(name: 'GS_RELEASE_CREDS', value: params.GS_RELEASE_CREDS),
                    string(name: 'GS_RELEASE_DOWNLOAD_ROOT', value: params.GS_RELEASE_DOWNLOAD_ROOT),
                    string(name: 'GS_RELEASE_ROOT', value: params.GS_RELEASE_ROOT),
                    string(name: 'MANIFEST_NAME', value: params.MANIFEST_NAME),
                    string(name: 'MANIFEST_TAG', value: params.MANIFEST_TAG),
                    string(name: 'MANIFEST_URL', value: params.MANIFEST_URL),
                    credentials(name: 'PACKET_CREDS', value: params.PACKET_CREDS),
                    string(name: 'PACKET_PROJECT', value: params.PACKET_PROJECT),
                    credentials(name: 'SIGNING_CREDS', value: params.SIGNING_CREDS),
                    string(name: 'SIGNING_USER', value: params.SIGNING_USER),
                    text(name: 'VERIFY_KEYRING', value: params.VERIFY_KEYRING),
                    string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
                ]
            else
                build job: 'vm-matrix', parameters: [
                    string(name: 'AWS_REGION', value: params.AWS_REGION),
                    credentials(name: 'AWS_RELEASE_CREDS', value: params.AWS_RELEASE_CREDS),
                    credentials(name: 'AWS_TEST_CREDS', value: params.AWS_TEST_CREDS),
                    credentials(name: 'AZURE_CREDS', value: params.AZURE_CREDS),
                    string(name: 'BOARD', value: params.BOARD),
                    credentials(name: 'BUILDS_CLONE_CREDS', value: params.BUILDS_CLONE_CREDS),
                    string(name: 'COREOS_OFFICIAL', value: params.COREOS_OFFICIAL),
                    text(name: 'FORMAT_LIST', value: format_list),
                    string(name: 'GROUP', value: params.GROUP),
                    credentials(name: 'GS_DEVEL_CREDS', value: params.GS_DEVEL_CREDS),
                    string(name: 'GS_DEVEL_ROOT', value: params.GS_DEVEL_ROOT),
                    credentials(name: 'GS_RELEASE_CREDS', value: params.GS_RELEASE_CREDS),
                    string(name: 'GS_RELEASE_DOWNLOAD_ROOT', value: params.GS_RELEASE_DOWNLOAD_ROOT),
                    string(name: 'GS_RELEASE_ROOT', value: params.GS_RELEASE_ROOT),
                    string(name: 'MANIFEST_NAME', value: params.MANIFEST_NAME),
                    string(name: 'MANIFEST_TAG', value: params.MANIFEST_TAG),
                    string(name: 'MANIFEST_URL', value: params.MANIFEST_URL),
                    credentials(name: 'PACKET_CREDS', value: params.PACKET_CREDS),
                    string(name: 'PACKET_PROJECT', value: params.PACKET_PROJECT),
                    credentials(name: 'SIGNING_CREDS', value: params.SIGNING_CREDS),
                    string(name: 'SIGNING_USER', value: params.SIGNING_USER),
                    text(name: 'VERIFY_KEYRING', value: params.VERIFY_KEYRING),
                    string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
                ]
        },
        'kola-qemu': {
            if (params.BOARD == 'amd64-usr')
                build job: '../kola/qemu', parameters: [
                    credentials(name: 'BUILDS_CLONE_CREDS', value: params.BUILDS_CLONE_CREDS),
                    credentials(name: 'DOWNLOAD_CREDS', value: UPLOAD_CREDS),
                    string(name: 'DOWNLOAD_ROOT', value: UPLOAD_ROOT),
                    string(name: 'MANIFEST_NAME', value: params.MANIFEST_NAME),
                    string(name: 'MANIFEST_TAG', value: params.MANIFEST_TAG),
                    string(name: 'MANIFEST_URL', value: params.MANIFEST_URL),
                    text(name: 'VERIFY_KEYRING', value: params.VERIFY_KEYRING),
                    string(name: 'PIPELINE_BRANCH', value: params.PIPELINE_BRANCH)
                ]
        }
}
