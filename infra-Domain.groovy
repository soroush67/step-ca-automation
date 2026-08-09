// Jenkins Pipeline job: "Pipeline script from SCM" pointing at this repo,
// with Script Path = infra-Domain.groovy
//
// Runs ansible-playbook (playbooks/issue_certificate.yml) on the step-ca
// SSH agent (192.168.11.4) to issue a cert for one domain and deploy it to
// the nginx gateway (192.168.11.2). See README.md for the underlying
// ansible role details.
//
// One-time Jenkins setup required before this works:
//   1. Set AGENT_NODE_LABEL below to the real label of the SSH agent node
//      you registered for 192.168.11.4 (Manage Jenkins > Nodes).
//   2. Create a "Secret text" credential holding the step-ca JWK
//      provisioner's actual password, with ID
//      PROVISIONER_PASSWORD_CREDENTIALS_ID below. It's bound straight to
//      the STEP_CA_PROVISIONER_PASSWORD env var that
//      roles/step_ca_issue reads - there's no separate vault file/password.
//   3. Confirm `ansible-playbook` is installed and on PATH for whichever
//      OS user the Jenkins agent runs as on 192.168.11.4, and that that
//      user can SSH as devops to 192.168.11.4 (itself) and 192.168.11.2.

def AGENT_NODE_LABEL = 'CHANGE_ME_STEP_CA_AGENT_LABEL'
def PROVISIONER_PASSWORD_CREDENTIALS_ID = 'step-ca-vault-password'

pipeline {
    agent { label AGENT_NODE_LABEL }

    options {
        disableConcurrentBuilds()
        timestamps()
    }

    parameters {
        string(name: 'DOMAIN', defaultValue: '', description: 'FQDN to issue a certificate for, e.g. elk.hamainsurance.net')
        string(name: 'BACKEND_IP', defaultValue: '', description: 'Backend service IP nginx will proxy_pass to')
        string(name: 'BACKEND_PORT', defaultValue: '', description: 'Backend service port')
        booleanParam(name: 'FORCE_REISSUE', defaultValue: false, description: 'Re-issue the certificate even if one already exists on the step-ca host')
    }

    stages {
        stage('Validate input') {
            steps {
                script {
                    def domainRe = /^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+$/
                    def ipv4Re = /^(\d{1,3}\.){3}\d{1,3}$/

                    if (!(params.DOMAIN ==~ domainRe)) {
                        error "DOMAIN '${params.DOMAIN}' does not look like a valid FQDN"
                    }
                    if (!(params.BACKEND_IP ==~ ipv4Re)) {
                        error "BACKEND_IP '${params.BACKEND_IP}' does not look like a valid IPv4 address"
                    }
                    def port = params.BACKEND_PORT.isInteger() ? params.BACKEND_PORT.toInteger() : -1
                    if (port < 1 || port > 65535) {
                        error "BACKEND_PORT '${params.BACKEND_PORT}' must be a number between 1 and 65535"
                    }
                }
            }
        }

        stage('Checkout automation repo') {
            steps {
                git branch: 'main', url: 'https://github.com/soroush67/step-ca-automation.git'
            }
        }

        stage('Issue certificate & deploy nginx site') {
            environment {
                DOMAIN = "${params.DOMAIN}"
                BACKEND_IP = "${params.BACKEND_IP}"
                BACKEND_PORT = "${params.BACKEND_PORT}"
                FORCE_REISSUE = "${params.FORCE_REISSUE}"
            }
            steps {
                // Values are passed via the shell environment (set above from
                // params, plus STEP_CA_PROVISIONER_PASSWORD bound below) and
                // double-quoted/read-by-ansible-directly, NOT interpolated into
                // this Groovy string - keeps user-supplied build parameters from
                // being able to inject shell commands, and keeps the password out
                // of argv (ansible reads it straight from the environment).
                withCredentials([string(credentialsId: PROVISIONER_PASSWORD_CREDENTIALS_ID, variable: 'STEP_CA_PROVISIONER_PASSWORD')]) {
                    sh '''
                        set -e
                        ansible-playbook playbooks/issue_certificate.yml \
                          -e domain="$DOMAIN" \
                          -e backend_ip="$BACKEND_IP" \
                          -e backend_port="$BACKEND_PORT" \
                          -e step_ca_force_reissue="$FORCE_REISSUE"
                    '''
                }
            }
        }
    }
}
