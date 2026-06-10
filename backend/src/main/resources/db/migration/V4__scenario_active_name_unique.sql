-- V4__scenario_active_name_unique.sql — make scenario-name uniqueness active-only.
--
-- V1 created a full UNIQUE(owner_id, name) constraint. Combined with soft-delete
-- (V3), that left an archived scenario holding its name forever — you could not
-- reuse the name of a deleted scenario. Scope uniqueness to LIVE rows: deleting a
-- scenario frees its name, while two *active* scenarios still cannot clash.
ALTER TABLE scenarios DROP CONSTRAINT IF EXISTS scenarios_owner_id_name_key;

-- Partial unique index: the rule applies only where deleted_at IS NULL. Its
-- leading owner_id column also serves the owner-scoped active-list query, so the
-- V3 helper index is now redundant.
DROP INDEX IF EXISTS idx_scenarios_owner_active;
CREATE UNIQUE INDEX scenarios_active_name_uniq
    ON scenarios (owner_id, name) WHERE deleted_at IS NULL;
