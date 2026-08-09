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
  never touches disk or the process list on the remote host.
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
2. Create the vaulted password file:
   ```
   cp inventory/sample/group_vars/step_ca/vault.yml.example \
      inventory/sample/group_vars/step_ca/vault.yml
   # edit step_ca_provisioner_password in it, then:
   ansible-vault encrypt inventory/sample/group_vars/step_ca/vault.yml
   ```
3. Confirm `ansible_user` (`devops` by default) has passwordless sudo on
   both hosts, and that `docker exec` on `.11.4` doesn't require sudo for
   that user (adjust `become` in `playbooks/issue_certificate.yml` if it
   does).

## Usage

```
ansible-playbook playbooks/issue_certificate.yml \
  -e domain=elk.hamainsurance.net \
  -e backend_ip=10.0.0.5 \
  -e backend_port=5601 \
  --ask-vault-pass
```

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
