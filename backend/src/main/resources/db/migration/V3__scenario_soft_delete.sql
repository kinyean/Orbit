-- V3__scenario_soft_delete.sql — soft-delete for scenarios.
--
-- DELETE /scenarios/{id} archives rather than hard-deletes. A hard delete would
-- CASCADE-wipe scenario_versions and SET NULL the audit_log version pointer
-- (see V1 ON DELETE policies), destroying the immutable history Frank relies on
-- (SRS §5.4.2). Instead we stamp deleted_at; list/get filter deleted_at IS NULL.
ALTER TABLE scenarios ADD COLUMN deleted_at TIMESTAMPTZ;

-- The owner-scoped active-list query path:
--   WHERE owner_id = ? AND deleted_at IS NULL ORDER BY created_at DESC.
-- A partial index over live rows keeps it lean (archived rows are excluded).
CREATE INDEX idx_scenarios_owner_active
    ON scenarios (owner_id)
    WHERE deleted_at IS NULL;
