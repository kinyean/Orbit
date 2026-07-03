# Deployment (Phase 10 — enterprise hardening)

How Orbit is deployed. Dev stays on Docker Compose; the **production/on-prem
deploy artifact is the Helm chart** (`deploy/helm/orbit`) — a Kubernetes install
with TLS, RBAC auth, secrets, and an air-gapped bundle path. See Decision 28 for
the WHY.

## Modes at a glance

| | Auth | Runtime | TLS | Secrets |
|---|---|---|---|---|
| **Local dev** | stub (dev user) | `docker compose up` | none | compose defaults |
| **Local dev + OIDC** | Keycloak | `docker compose -f docker-compose.yml -f docker-compose.oidc.yml up` | none | compose (dev creds) |
| **Prod / on-prem** | OIDC (Keycloak or external IdP) | `helm install` on Kubernetes | cert-manager @ ingress | k8s Secrets |

## Auth modes (US-AUTH-02/03)

Two interchangeable modes, chosen by `orbit.auth.mode` (backend) / `VITE_AUTH_MODE`
or the runtime `/env.js` (frontend):

- **stub** — a fixed dev user is injected and everything is permitted (Phase-1
  posture). The default, so nothing extra is needed for local dev.
- **oidc** — the backend is an OAuth2 **resource server** validating OIDC bearer
  JWTs (stateless); the SPA runs auth-code + PKCE and sends the token (REST via the
  `Authorization` header, WebSocket via `?access_token=`). Roles come from the
  Keycloak realm-role claim → `ROLE_*` authorities; per-scenario **ownership** is
  enforced independently in `ScenarioService` (a non-owner gets 404, no enumeration).

**Issuer consistency (the one thing to get right):** the token's `iss` must be a
single URL both the browser and the backend resolve to the same Keycloak.
- *Compose+OIDC:* `http://host.docker.internal:8082/realms/orbit` (backend reaches
  it via `extra_hosts: host-gateway`; add `host.docker.internal` to the browser
  machine's hosts if not on Docker Desktop — see `docker-compose.oidc.yml`).
- *Helm:* Keycloak sits behind the ingress at its own host (`keycloak.host`), so
  `auth.issuerUrl = https://<keycloak.host>/realms/orbit` is one public URL both
  sides use — no mismatch.

### Local dev with OIDC

```
docker compose -f docker-compose.yml -f docker-compose.oidc.yml up --build
```
Then open the frontend and sign in as a seed user (`deploy/keycloak/orbit-realm.json`):
`maya/maya` (mission_planner), `frank/frank` (flight_dynamics_engineer, admin),
`gita/gita` (flight_dynamics_engineer).

## Kubernetes install (Helm)

### Prerequisites (cluster add-ons)
- An **ingress controller** — the chart uses nginx-ingress annotations.
- **cert-manager** with a ClusterIssuer (e.g. `letsencrypt-prod`) for TLS, unless
  you provision the TLS secret out-of-band.

### Install
```
helm install orbit deploy/helm/orbit -n orbit --create-namespace -f my-values.yaml
```
A minimal `my-values.yaml`:
```yaml
image:
  registry: registry.internal/orbit
ingress:
  host: orbit.example.com
  tls: { enabled: true, secretName: orbit-tls, clusterIssuer: letsencrypt-prod }
auth:
  mode: oidc
  issuerUrl: https://keycloak.example.com/realms/orbit
keycloak:
  host: keycloak.example.com
secrets:
  dbPassword: "<db>"
  keycloakAdminPassword: "<admin>"
```
Point DNS for both hosts at the ingress controller; watch `kubectl get certificate -n orbit`.

### Toggles
- **External database:** `--set postgres.enabled=false` and set
  `postgres.external.url` / `postgres.external.user` (+ the `db-password` secret).
- **External IdP** (instead of in-cluster Keycloak): `--set keycloak.enabled=false`
  and set `auth.issuerUrl` to your IdP's issuer + register the `orbit-frontend`
  client (public, PKCE, redirect `https://<ingress.host>/*`).
- **GitOps secrets:** `--set secrets.create=false` and supply a Secret named
  `orbit-secrets` (keys `db-password`, `keycloak-admin-password`) via
  sealed-secrets / external-secrets.

## TLS + secrets (US-INFRA-08)

- **TLS** terminates at the ingress; cert-manager issues/renews the certs. The SPA
  and `/api` (REST + WebSocket) share one host, so the browser is same-origin (no
  prod CORS); the API ingress strips the `/api` prefix before forwarding.
- **Secrets** are Kubernetes `Secret`s injected as env (`secretKeyRef`). Nothing
  sensitive is in `values.yaml` defaults or git when `secrets.create=false`. The
  dev Compose keeps its throwaway `orbit/orbit` credentials.

## Air-gapped bundle (US-INFRA-09)

```
CESIUM_ION_TOKEN=<token> scripts/bundle.sh 0.1.0
```
Produces `dist-bundle/orbit-images-0.1.0.tar` (all app + infra images) and
`dist-bundle/orbit-0.1.0.tgz` (the packaged chart). On the disconnected host:
```
docker load -i orbit-images-0.1.0.tar
# tag + push each image into the private/in-cluster registry
helm install orbit orbit-0.1.0.tgz -f my-values.yaml
```

## Known limitations / follow-ups

- **Keycloak in-cluster is single-instance, H2-backed** (`start-dev --import-realm`)
  — a dev/demo IdP, not HA. Production Keycloak HA (its own DB + clustering), or an
  external IdP, is the hardening step (Decision 28).
- **Frontend runtime config** covers auth (`/env.js`); the public Cesium ion token
  is still baked at image build (`--build-arg VITE_CESIUM_ION_TOKEN`).
- The chart is validated with `helm lint` + `helm template`; a full end-to-end
  install (ingress + TLS + OIDC round-trip) is validated on the target cluster.
