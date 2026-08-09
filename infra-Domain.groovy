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
//   2. Create a "Secret text" credential holding the step-ca provisioner's
//      vault password, with ID VAULT_CREDENTIALS_ID below (must match
//      step_ca_provisioner_password as encrypted in
//      inventory/sample/group_vars/step_ca/vault.yml).
//   3. Confirm `ansible-playbook` is installed and on PATH for whichever
//      OS user the Jenkins agent runs as on 192.168.11.4, and that that
//      same user can SSH to itself (the "step_ca" play targets
//      192.168.11.4, and the pipeline now runs ON 192.168.11.4 - if
//      self-SSH isn't already set up, either enable it or add
//      `ansible_connection: local` for that host in the inventory).

def AGENT_NODE_LABEL = 'CHANGE_ME_STEP_CA_AGENT_LABEL'
def VAULT_CREDENTIALS_ID = 'step-ca-vault-password'

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
                withCredentials([string(credentialsId: VAULT_CREDENTIALS_ID, variable: 'VAULT_PASS')]) {
                    sh '''
                        set -e
                        umask 077
                        printf '%s' "$VAULT_PASS" > vault_pass.txt
                    '''
                    script {
                        try {
                            // Values are passed via the shell environment ($DOMAIN etc, set
                            // above from params) and double-quoted here, NOT interpolated
                            // into this Groovy string - keeps user-supplied build
                            // parameters from being able to inject shell commands.
                            sh '''
                                set -e
                                ansible-playbook playbooks/issue_certificate.yml \
                                  -e domain="$DOMAIN" \
                                  -e backend_ip="$BACKEND_IP" \
                                  -e backend_port="$BACKEND_PORT" \
                                  -e step_ca_force_reissue="$FORCE_REISSUE" \
                                  --vault-password-file vault_pass.txt
                            '''
                        } finally {
                            sh 'rm -f vault_pass.txt'
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            sh 'rm -f vault_pass.txt || true'
        }
    }
}
