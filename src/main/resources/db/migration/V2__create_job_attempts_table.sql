-- Her denemenin kaydı. Satır claim anında açılır, sonuç yazılınca kapanır.
CREATE TABLE job_attempts (
    id             BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id         UUID         NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    attempt_no     INTEGER      NOT NULL,
    worker_id      VARCHAR(120) NOT NULL,
    started_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at    TIMESTAMPTZ,
    outcome        VARCHAR(24),
    error_summary  TEXT,

    CONSTRAINT uq_job_attempts_job_attempt UNIQUE (job_id, attempt_no),
    CONSTRAINT chk_job_attempts_attempt_no CHECK (attempt_no > 0),
    CONSTRAINT chk_job_attempts_outcome CHECK (
        outcome IN ('SUCCESS', 'TRANSIENT_FAILURE', 'PERMANENT_FAILURE', 'LEASE_EXPIRED')
    ),
    -- bitmiş denemenin sonucu olmalı, devam edeninki olmamalı
    CONSTRAINT chk_job_attempts_finished CHECK ((finished_at IS NULL) = (outcome IS NULL))
);
