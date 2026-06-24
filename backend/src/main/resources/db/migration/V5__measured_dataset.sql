-- V5__measured_dataset.sql — stored measured-ephemeris artifacts.
--
-- Importing a satellite's real telemetry (WOD CSV → ECI position/velocity over a
-- window) stores the samples here, OUT of the small jsonb scenario body. A
-- scenario role references a dataset by id (InitialState{kind:"ephemeris",
-- datasetId}); the backend serves it via an Orekit tabulated Ephemeris. The
-- dataset is immutable + content-hashed, so a referencing scenario reproduces
-- byte-for-byte (R11) — the larger-artifact analogue of the frozen-TLE snapshot.
CREATE TABLE measured_dataset (
    id             UUID PRIMARY KEY,
    owner_id       UUID NOT NULL REFERENCES users (id),
    satellite_name TEXT NOT NULL,
    norad_id       INTEGER,
    frame          TEXT NOT NULL,
    start_utc      TIMESTAMPTZ NOT NULL,
    end_utc        TIMESTAMPTZ NOT NULL,
    sample_count   INTEGER NOT NULL,
    source_name    TEXT,
    content_hash   TEXT NOT NULL,
    samples        BYTEA NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Owner-scoped listing (and the RBAC seam, Decision 16).
CREATE INDEX idx_measured_dataset_owner ON measured_dataset (owner_id);
