-- V1__init.sql — initial schema for the Orbit RPO simulation platform.
--
-- Tables:
--   users              — operator identities (RBAC seam from day one)
--   scenarios          — the persistent identity of an RPO scenario
--   scenario_versions  — immutable snapshots; one row per edit (Git-style history)
--   audit_log          — actions taken (mutating and non-mutating)
--
-- Conventions:
--   - All PKs are UUIDs generated via gen_random_uuid() (Postgres 13+ built-in,
--     no extension needed).
--   - created_at columns default to now() (UTC by default in Postgres).
--   - jsonb for flexible-but-queryable scenario bodies.
--   - Foreign keys enforce referential integrity. ON DELETE policies:
--       * CASCADE when the child is owned by the parent (versions/audit
--         under a scenario)
--       * RESTRICT for users (block deleting a user with active scenarios)
--       * SET NULL for soft links (scenarios.latest_version_id, audit
--         pointing at a deleted version)

-- ---------------------------------------------------------------------------
-- users — identity. RBAC seam present from Phase 1 even though auth is stubbed.
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Display name + human-readable identifier. Even with SSO, you want to see
    -- "Maya Chen" not "auth0|abc123" in the UI.
    email        VARCHAR(255) NOT NULL UNIQUE,
    -- The OIDC/SAML `sub` claim — stable IdP identifier. Nullable in stub mode;
    -- filled when SSO activates (Phase 10). See decisions.md §1 (Frontend) and
    -- §16 (enterprise posture).
    sso_subject  VARCHAR(255) UNIQUE,
    -- RBAC roles, e.g. ARRAY['mission_planner','admin']. Read by Spring
    -- Security when auth is wired (Phase 10).
    roles        TEXT[]       NOT NULL DEFAULT ARRAY[]::TEXT[],
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- scenarios — the *identity* of a scenario. Like a Git repo. Mutable shell.
-- See decisions.md §14 (scenario data model).
-- ---------------------------------------------------------------------------
CREATE TABLE scenarios (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Ownership is enforced from day one (RBAC seam, decisions.md §16).
    owner_id           UUID         NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    name               VARCHAR(255) NOT NULL,
    -- Pointer to the most recent version (denormalized for fast reads).
    -- Nullable for the brief window between scenario creation and v1 write,
    -- and as a safety belt if the version is later removed.
    latest_version_id  UUID,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Scenarios are unique by name *per owner*.
    UNIQUE (owner_id, name)
);

-- ---------------------------------------------------------------------------
-- scenario_versions — immutable history. Each edit creates a new row.
-- ---------------------------------------------------------------------------
CREATE TABLE scenario_versions (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    scenario_id  UUID         NOT NULL REFERENCES scenarios(id) ON DELETE CASCADE,
    version_no   INTEGER      NOT NULL,
    author_id    UUID         NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- The scenario contents: chief, deputies, maneuvers, sensors, attitude,
    -- fidelity, time range. jsonb gives flexibility + queryability.
    body         JSONB        NOT NULL,
    -- Versions are unique by (scenario, version_no). Application enforces
    -- the monotonic version_no = max(prev) + 1 within a scenario.
    UNIQUE (scenario_id, version_no)
);

-- Now that scenario_versions exists, wire the FK from scenarios.latest_version_id.
-- (Two-step add avoids a circular dependency at CREATE time.)
ALTER TABLE scenarios
    ADD CONSTRAINT scenarios_latest_version_fk
    FOREIGN KEY (latest_version_id) REFERENCES scenario_versions(id) ON DELETE SET NULL;

-- ---------------------------------------------------------------------------
-- audit_log — actions taken (both state-mutating and non-mutating).
-- Captures the SRS §5.4.2 audit requirement; the canonical "who did what when"
-- log for compliance.
-- ---------------------------------------------------------------------------
CREATE TABLE audit_log (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Nullable to allow non-scenario actions (e.g., login events, system tasks).
    scenario_id   UUID         REFERENCES scenarios(id) ON DELETE CASCADE,
    -- Points at the version produced/consumed by the action, when applicable.
    -- SET NULL on version delete so audit history isn't lost.
    version_id    UUID         REFERENCES scenario_versions(id) ON DELETE SET NULL,
    actor_id      UUID         NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    -- Varchar (not enum) so adding new action types doesn't require a
    -- migration. Canonical actions are documented in audit conventions.
    action        VARCHAR(64)  NOT NULL,
    timestamp     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Short human-readable description, e.g. "Added maneuver at T+02:00 for
    -- Deputy-1". Saves auditors from computing diffs themselves.
    diff_summary  TEXT
);

-- ---------------------------------------------------------------------------
-- Indexes for common query paths.
-- ---------------------------------------------------------------------------

-- Version history lookups (e.g., GET /scenarios/{id}/versions).
CREATE INDEX idx_scenario_versions_scenario ON scenario_versions (scenario_id);

-- "All actions against this scenario."
CREATE INDEX idx_audit_log_scenario ON audit_log (scenario_id);

-- "What has this user done recently?" — RBAC + compliance review.
CREATE INDEX idx_audit_log_actor_time ON audit_log (actor_id, timestamp DESC);
