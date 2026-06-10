-- V2__seed_dev_user.sql — seed the stub dev user.
--
-- The Phase-1 schema makes scenarios.owner_id a NOT NULL FK to users(id), but
-- no users row exists yet. DevUserAuthenticationFilter injects a fixed dev
-- principal on every request (id 00000000-0000-0000-0000-000000000001,
-- email dev@orbit.local); scenario ownership and audit rows reference that id.
-- Seed it here so the first POST /scenarios has a valid owner.
--
-- Constants mirror DevUserAuthenticationFilter.DEV_USER_ID / DEV_USER_EMAIL.
-- Idempotent: re-running the migration set (or running against a DB where a
-- real IdP later provisions the same id) is a no-op.
INSERT INTO users (id, email, roles)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'dev@orbit.local',
    ARRAY['mission_planner', 'flight_dynamics_engineer', 'admin']::TEXT[]
)
ON CONFLICT (id) DO NOTHING;
