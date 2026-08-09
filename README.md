# step-ca

Automates the manual flow: issue a certificate from step-ca (running in
Docker on `192.168.11.4`), copy it to the nginx gateway (`192.168.11.2`),
render a site config from it, enable the site, and reload nginx.

## Layout

- `roles/step_ca_issue` - runs on the `step_ca` group. Issues (or reuses) a
  cert/key pair via `docker exec ... step ca certificate` using a JWK
  provisioner, non-interactively.
- `roles/nginx_tls_site` - runs on the `nginx_gateway` group. Pulls the
  cert/key from the step-ca host, deploys them, renders
  `sites-available/<domain>.conf`, symlinks it into `sites-enabled`, then
  validates (`nginx -t`) and reloads nginx.
- `playbooks/issue_certificate.yml` - runs both roles for one domain per
  invocation.

## Design assumptions (confirmed with the user)

- **Provisioner type: JWK with a password.** The interactive "token" prompt
  from `step ca certificate` is step-ca's password prompt for a JWK
  provisioner. It's bypassed with `--provisioner-password-file /dev/stdin`,
  fed via Ansible's `stdin` param on the `command` task - the password
  never touches disk or the process list on the remote host. The password
  value itself comes from the `STEP_CA_PROVISIONER_PASSWORD` environment
  variable (see Setup below), not from `-e` or an ansible-vault file.
- **One domain per run**, passed via `-e domain=... -e backend_ip=...
  -e backend_port=...` - matches how you're issuing certs today (one
  `docker exec` / `scp` / symlink per domain).
- Cert/key are moved step-ca-host -> Ansible controller -> nginx-host via
  `slurp` + `copy` (in-memory, not written to the controller's disk), so it
  only needs the SSH access Ansible already has to both hosts - it doesn't
  depend on the `devops` user having direct SSH trust between .11.4 and
  .11.2 the way the manual `scp` commands do.
- Re-running for a domain that already has a cert on the step-ca host is a
  no-op for the issuance step (checked via `stat`); set
  `-e step_ca_force_reissue=true` to force renewal.

## Setup (one-time)

1. Set the real provisioner name in
   `inventory/sample/group_vars/step_ca/main.yml` (`step_ca_provisioner`,
   currently `CHANGE_ME`).
2. Export the provisioner's actual password as `STEP_CA_PROVISIONER_PASSWORD`
   before running ansible-playbook - it's read via an environment lookup
   (`roles/step_ca_issue/defaults/main.yml`), never passed as `-e` (that
   would land in argv, visible to `ps` on the host) and never written to
   disk. There's no ansible-vault file involved; the password just needs
   to be in the process environment when `ansible-playbook` runs. See the
   Jenkins section below for how the pipeline supplies it.
3. The `step_ca` play runs without `become` (relies on `devops` already
   being in the `docker` group and owning `step_ca_host_certs_dir`/
   `step_ca_host_secrets_dir`). The `nginx_gateway` play still uses
   `become: true` for `/etc/nginx` and the service reload - confirm
   `devops` has passwordless sudo there.

## Usage

```
STEP_CA_PROVISIONER_PASSWORD=... ansible-playbook playbooks/issue_certificate.yml \
  -e domain=elk.hamainsurance.net \
  -e backend_ip=10.0.0.5 \
  -e backend_port=5601
```

## CI/CD (Jenkins)

`infra-Domain.groovy` is a Declarative Pipeline that wraps `ansible-playbook
playbooks/issue_certificate.yml` behind an interactive "Build with
Parameters" form (`DOMAIN`, `BACKEND_IP`, `BACKEND_PORT`,
`FORCE_REISSUE`), so it can be run without CLI access.

Set up as a Jenkins Pipeline job: "Pipeline script from SCM", pointing at
this repo, with **Script Path** = `infra-Domain.groovy`.

One-time setup, before the first run:

1. In `infra-Domain.groovy`, set `AGENT_NODE_LABEL` to the actual label of
   the SSH agent node registered for `192.168.11.4`.
2. Create a Jenkins **Secret text** credential holding the step-ca
   provisioner's actual password, with ID `step-ca-vault-password` (or
   change `PROVISIONER_PASSWORD_CREDENTIALS_ID` in the pipeline to
   whatever ID you use). It's bound directly to the
   `STEP_CA_PROVISIONER_PASSWORD` env var - no vault file involved.
3. Confirm `ansible-playbook` is on `PATH` for the OS user the Jenkins
   agent runs as on `.11.4`. That user (via its SSH key) needs to be able
   to `ssh devops@192.168.11.4` (itself - confirmed working) and
   `ssh devops@192.168.11.2`.

Note: even though the pipeline's agent process runs directly on
`192.168.11.4`, the `step_ca` play still connects over SSH as `devops`
rather than executing locally as the Jenkins agent's own OS user - the
agent user isn't in the `docker` group and has no passwordless sudo,
whereas `devops` already has exactly the access the manual workflow used.

The pipeline never prints the provisioner password: it's bound via
`withCredentials` (Jenkins auto-masks it in the log) straight to
`STEP_CA_PROVISIONER_PASSWORD`, which `step_ca_issue`'s defaults read via
an environment lookup - it's never passed as `-e` or written to a file.
Build parameters are validated (FQDN/IPv4/port format) and passed to the
shell via environment variables rather than Groovy string interpolation,
to rule out shell injection from the input form.

## Not yet verified

This was scaffolded without SSH access to `192.168.11.4` / `.11.2` from the
authoring environment (no route to either host), so it hasn't been run
end-to-end. Before trusting it against a real domain:

- Confirm the provisioner name and that `--provisioner-password-file
  /dev/stdin` actually satisfies your step-ca's prompt (some step-ca
  configs may need `--provisioner-password-file` pointed at a real file
  inside the container instead of `/dev/stdin`, depending on how `docker
  exec -i` pipes stdin through).
- Run once against a throwaway/test domain first and inspect
  `/etc/nginx/sites-available/<domain>.conf` and the reload result before
  relying on it for production domains.
- `infra-Domain.groovy` has run against a real Jenkins instance and gotten
  past checkout and the step-ca SSH connection; the actual `step ca
  certificate` call and the `nginx_gateway` play (including the `devops`
  passwordless-sudo assumption there) are still unverified end-to-end.
