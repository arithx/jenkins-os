#!/usr/bin/env groovy

def creds = [
  file(credentialsId: 'tectonic-license', variable: 'TF_VAR_tectonic_license_path'),
  file(credentialsId: 'tectonic-pull', variable: 'TF_VAR_tectonic_pull_secret_path'),
  [
    $class: 'UsernamePasswordMultiBinding',
    credentialsId: 'tectonic-console-login',
    passwordVariable: 'TF_VAR_tectonic_admin_password_hash',
    usernameVariable: 'TF_VAR_tectonic_admin_email'
  ],
  [
    $class: 'AmazonWebServicesCredentialsBinding',
    credentialsId: 'c7e3cb5d-0c69-46c8-b184-48d68b1ce680'
  ]
]

def default_builder_image = 'quay.io/coreos/tectonic-builder:v1.36'
def tectonic_smoke_test_env_image = 'quay.io/coreos/tectonic-smoke-test-env:v3.0'

pipeline {
  agent none
  options {
    timeout(time:70, unit:'MINUTES')
    timestamps()
    buildDiscarder(logRotator(numToKeepStr:'100'))
  }
  parameters {
    string(
      name: 'builder_image',
      defaultValue: default_builder_image,
      description: 'tectonic-builder docker image to use for builds'
    )
  }

  stages {
    stage('Build & Test') {
      environment {
        GO_PROJECT = '/go/src/github.com/coreos/tectonic-installer'
        MAKEFLAGS = '-j4'
      }
      steps {
        node('amd64 && docker') {
          withDockerContainer(params.builder_image) {
            sh """#!/bin/bash -ex
            git clone https://github.com/coreos/tectonic-installer

            mkdir -p \$(dirname ${GO_PROJECT}) && ln -sf \$(pwd)/tectonic-installer ${GO_PROJECT}

            cd ${GO_PROJECT}/
            ls
            make bin/smoke

            cd ${GO_PROJECT}/installer
            make clean
            make tools
            make build

            make dirtycheck
            make lint
            make test
            rm -fr frontend/tests_output
            """
            stash name: 'installer', includes: 'installer/bin/linux/installer'
            stash name: 'node_modules', includes: 'installer/frontend/node_modules/**'
            stash name: 'smoke', includes: 'bin/smoke'
          }
          withDockerContainer(tectonic_smoke_test_env_image) {
            checkout scm
            sh"""#!/bin/bash -ex
              cd tests/rspec
              bundler exec rubocop --cache false tests/rspec
            """
          }
        }
      }
    }

    stage("Tests") {
      environment {
        TECTONIC_INSTALLER_ROLE = 'tectonic-installer'
      }
      steps {
        parallel (
          "SmokeTest AWS RSpec on CL nightly": {
            node('amd64 && docker') {
              withCredentials(creds) {
                checkout scm
                unstash 'installer'
                unstash 'smoke'
                withDockerContainer(tectonic_smoke_test_env_image) {
                  checkout scm
                  unstash 'installer'
                    sh """#!/bin/bash -ex
                      git clone https://github.com/coreos/tectonic-installer

                      # Update the AMI
                      source <(curl -s https://storage.googleapis.com/builds.developer.core-os.net/boards/amd64-usr/current-master/version.txt)
                      AMI=\$(curl -s https://storage.googleapis.com/builds.developer.core-os.net/boards/amd64-usr/${COREOS_VERSION}/coreos_production_ami_all.json | jq -cr '.amis[] | select(.name == "us-west-2") | .hvm')
                      sed -i "s/\${data.aws_ami.coreos_ami.image_id}/\${AMI}/g" ${GO_PROJECT}/modules/aws/master-asg/master.tf
                      sed -i "s/\${data.aws_ami.coreos_ami.image_id}/\${AMI}/g" ${GO_PROJECT}/modules/aws/worker-asg/worker.tf
                      sed -i "s/\${data.aws_ami.coreos_ami.image_id}/\${AMI}/g" ${GO_PROJECT}/modules/aws/etcd/nodes.tf

                      # Update the base domain in vars
                      find ${GO_PROJECT}/tests/smoke/aws/vars/ -type f -exec sed -i "s|tectonic.dev.coreos.systems|clnightly.dev.coreos.systems|g" {} \\;
                      find ${GO_PROJECT}/tests/smoke/aws/vars/ -type f -exec sed -i "s|eu-west-1|us-west-2|g" {} \\;

                      sed -i "s|eu-west-1|us-west-2|g" examples/terraform.tfvars.aws

                      # Update the regions & base domain in smoke.sh
                      sed -i "s|REGIONS=.*|REGIONS=(us-west-2)|g" tests/smoke/aws/smoke.sh
                      sed -i "s|TF_VAR_base_domain=.*|TF_VAR_base_domain=${TF_VAR_tectonic_base_domain}|g" tests/smoke/aws/smoke.sh

                      cd tests/rspec
                      bundler exec rspec
                    """
                }
              }
            }
          }
        )
      }
    }
  }
}