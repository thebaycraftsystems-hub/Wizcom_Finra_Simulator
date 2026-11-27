# GitHub access — FINRA FIX Simulator

Developers must use the **GitHub** remote, not the internal Gitea URL in some local clones.

## Correct repository (GitHub)

| Item | Value |
|------|--------|
| Organization | `thebaycraftsystems-hub` |
| Repository name | **`Wizcom_Finra_Simulator`** (not `fix-simulator`) |
| Web URL | https://github.com/thebaycraftsystems-hub/Wizcom_Finra_Simulator |
| Default / active branch | `main` |

## Clone (read access)

Public read access is available without a token:

```bash
git clone https://github.com/thebaycraftsystems-hub/Wizcom_Finra_Simulator.git
cd Wizcom_Finra_Simulator
```

## Push access (write)

Pushing requires:

1. A **GitHub account**
2. **Membership** in the `thebaycraftsystems-hub` organization (or collaborator on the repo)
3. For HTTPS: a **Personal Access Token (PAT)** or **GitHub CLI** login — password-only auth is not supported

```bash
git remote add github https://github.com/thebaycraftsystems-hub/Wizcom_Finra_Simulator.git
git push github main
```

An org admin must invite users: **GitHub → Organization → People → Invite member**, or **Repo → Settings → Collaborators**.

## Do not use `origin` unless on corporate network

Many workstations have a second remote pointing at **internal Gitea**:

```text
origin  http://subhash@192.168.1.66:3000/wizcom/fix-simulator
```

That server is only reachable on the **company LAN / VPN**. It is **not** GitHub. Symptoms:

- `Could not resolve host` / connection timeout
- `403` / `401` from `192.168.1.66`

**Fix:** Clone from the GitHub URL above, or add:

```bash
git remote add github https://github.com/thebaycraftsystems-hub/Wizcom_Finra_Simulator.git
git fetch github
```

## Common errors and fixes

| Symptom | Likely cause | Fix |
|---------|----------------|-----|
| Repository not found | Wrong name (`fix-simulator` vs `Wizcom_Finra_Simulator`) | Use exact URL in this doc |
| 404 on GitHub web | Not logged in + private repo, or wrong org | Ask admin for invite; confirm org name |
| Permission denied (push) | Not a collaborator | Org admin adds user or team |
| SSO authorization failed | Enterprise SAML | Open GitHub → authorize SSO for the org |
| Timeout to `192.168.1.66` | Using `origin` off VPN | Use GitHub HTTPS URL |

## Build after clone

```bash
mvn clean package -DskipTests
java -jar target/fix-simulator-*-jar-with-dependencies.jar primary
```

Config: copy or edit `quickfixj-server.cfg` in the run directory (see `src/main/resources/com/wizcom/fix/simulator/quickfixj-server.cfg`).

## Admin checklist (for team lead)

- [ ] Confirm repo visibility (public vs private) under **Settings → General**
- [ ] Invite developers to `thebaycraftsystems-hub` or add as repo collaborators
- [ ] Share this document and the **exact clone URL**
- [ ] Confirm corporate firewall allows `github.com` (443)
- [ ] Clarify whether team should use **GitHub** or **Gitea** (`192.168.1.66:3000`) — not both interchangeably without VPN

---

*Verified: `git ls-remote` to GitHub succeeds for `main`.*
