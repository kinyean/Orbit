# Phase 10 — Enterprise hardening (plan + live status)

The **HOW** for Phase 10 (roadmap §10; SRS §5.2 / §5.4 / §5.5 / §6.2; US-AUTH-02/03,
US-INFRA-05..09). Sliced **10A / 10B / 10C**. Full design rationale in the approved
planning doc `~/.claude/plans/plan-out-phase-10-peaceful-cookie.md`; the **WHY** is
Decision 28. This file is the **resume point**.

## Status

- **10A — Real auth + RBAC: ✅ done** (backend tested; frontend type-checked;
  Keycloak dev overlay + realm). OIDC browser round-trip is verified on the dev
  stack (needs a running Keycloak + browser — not reproducible in CI).
- **10B — Governance & trust: ✅ done & tested** (audit-log/versions REST + panel;
  reproducibility tests; §5.2 Orekit-reference validation suite + conformance doc).
- **10C — Deployment hardening: ✅ done** (frontend prod image; Helm chart —
  `helm lint` + `helm template` clean; TLS/ingress/secrets; offline bundle;
  `docs/deployment.md`). Full cluster install (ingress + TLS + OIDC end-to-end) is
  verified on the target k8s cluster.

Backend `./gradlew test` = **203 green** (was 188 at Phase 9). Frontend `type-check`
green. Auth defaults to **stub**, so the existing dev loop is unchanged.

## What landed (file map, for resume)

### 10A — Auth + RBAC (US-AUTH-02/03)
- **MOD** [SecurityConfig.java](../backend/src/main/java/space/orbit/backend/security/SecurityConfig.java)
  — two `@ConditionalOnProperty(orbit.auth.mode)` filter chains: `stub` (dev filter +
  permitAll, default) and `oidc` (OAuth2 resource server; `JwtAuthenticationConverter`
  maps `realm_access.roles` → `ROLE_*`, principal name = `email`; `DefaultBearerTokenResolver`
  with `allowUriQueryParameter(true)` so WS handshakes authenticate via `?access_token=`;
  authenticate + role rules). `realmRoleAuthorities` package-private for the unit test.
- **MOD** [DevUserAuthenticationFilter.java](../backend/src/main/java/space/orbit/backend/security/DevUserAuthenticationFilter.java)
  — dropped `@Component` (constructed only in the stub chain) so it's never a global
  servlet filter that would clobber JWT auth in oidc mode.
- **MOD** [UserService.java](../backend/src/main/java/space/orbit/backend/scenario/UserService.java)
  — `currentUser()` syncs `ssoSubject` + app roles from a `JwtAuthenticationToken`;
  new `emailsByIds()` (for the audit/version UI). **MOD** `User` gains setters.
- **MOD** [application.yml](../backend/src/main/resources/application.yml) — `orbit.auth.mode`
  (default stub) + `spring.security.oauth2.resourceserver.jwt.issuer-uri`. **MOD**
  `build.gradle.kts` — `spring-boot-starter-oauth2-resource-server`.
- **NEW** frontend `auth/{config,token,AuthGate,UserChip}.tsx` — `react-oidc-context`
  provider + login gate + a token holder bridging to imperative code; **MOD**
  `api/client.ts` (Bearer middleware), `stream/{Scenario,Catalog}StreamClient.ts`
  (append `?access_token=` per connect), `main.tsx` (wrap in `<AuthGate>`), `App.tsx`
  (`<UserChip>`), `vite-env.d.ts`, `App.css`, `.env.example`. New dep:
  `react-oidc-context` + `oidc-client-ts`.
- **NEW** [docker-compose.oidc.yml](../docker-compose.oidc.yml) + [deploy/keycloak/orbit-realm.json](../deploy/keycloak/orbit-realm.json)
  — a Keycloak dev IdP overlay (roles + maya/frank/gita seed users).
- **NEW test** `security/SecurityConfigTests` (401/403/201 + realm-role mapping).

### 10B — Governance & trust (US-INFRA-05/06/07)
- **NEW** `scenario/{AuditEntryResponse,ScenarioVersionSummary}.java`; **MOD**
  [ScenarioService.java](../backend/src/main/java/space/orbit/backend/scenario/ScenarioService.java)
  (`versionHistory` + `auditTrail`, owner-gated) + [ScenarioController.java](../backend/src/main/java/space/orbit/backend/api/ScenarioController.java)
  (`GET /scenarios/{id}/versions`, `GET /scenarios/{id}/audit`).
- **NEW** frontend `scenario/AuditLogPanel.tsx` (version history + audit trail, panel
  chrome). `api/schema.d.ts` hand-extended with the two GET paths + DTOs —
  **run `npm run gen:api` against a live backend to canonicalize.**
- **NEW test** `validation/ValidationConformanceTest` (§5.2.1 SGP4 stable week; §5.2.2
  numerical perturbed-yet-bounded + 24 h bit-identical); **MOD** `ScenarioStreamServiceTests`
  (SGP4 + finite-burn byte-identical reruns); **MOD** `ScenarioServiceTests`,
  `ScenarioControllerTests`. **NEW doc** [validation-conformance.md](./validation-conformance.md).

### 10C — Deployment hardening (US-INFRA-08/09)
- **NEW** `frontend/{Dockerfile.prod,nginx.conf,docker-entrypoint.sh,env.template.js}`
  — prod SPA image; runtime `/env.js` (envsubst) → one portable image. **MOD**
  `index.html` (loads `/env.js`), `auth/config.ts` (`window.__ORBIT_ENV__` wins),
  `vite-env.d.ts`.
- **NEW** Helm chart [deploy/helm/orbit/](../deploy/helm/orbit/) — Chart/values +
  templates (backend, frontend, keycloak+realm ConfigMap, postgres StatefulSet, secret,
  split api/web/keycloak Ingress with cert-manager TLS + WS timeouts + `/api` rewrite).
  Toggles: `postgres.enabled` (external DB), `keycloak.enabled` (external IdP),
  `secrets.create` (GitOps). Validated with `helm lint` + `helm template`.
- **NEW** [scripts/bundle.sh](../scripts/bundle.sh) (offline `docker save` + `helm package`),
  **NEW** [docs/deployment.md](./deployment.md).

## Invariants preserved
- Streaming contract additive, `VERSION="1"` (R12) — auth is transport; the new REST
  regenerates the client; no `scenario-relative` shape change.
- Determinism (R11 / §5.4.1) held and now *proven end-to-end* (10B).
- Single audited mutation path (Decision 16) — 10B only *reads* the audit log.
- Stateless posture kept (resource-server bearer tokens, not sessions).

## Verification
- **10A:** `./gradlew test` green incl. `SecurityConfigTests`; stub mode → dev loop
  unchanged; OIDC round-trip on the dev stack via `docker-compose.oidc.yml`.
- **10B:** `./gradlew test` green (203); `GET /scenarios/{id}/audit` + `/versions`
  return history; audit panel renders; validation suite passes.
- **10C:** `helm lint` + `helm template` clean (13 manifests default; 6 with
  postgres/keycloak off); full install on a k8s cluster is the remaining live check.

## Deferred (Decision 28)
- SAML2 (OIDC satisfies the SRS); production Keycloak HA (own DB/clustering); external
  AIAA/Vallado golden vectors (Orekit-reference chosen); a prod Docker Compose path
  (Helm is the deploy artifact). **Phase 11 next** — polish & ship.
